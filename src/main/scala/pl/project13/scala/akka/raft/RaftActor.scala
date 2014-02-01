package pl.project13.scala.akka.raft

import akka.actor.{Actor, ActorRef, LoggingFSM}
import scala.concurrent.duration._
import scala.util.Random
import scala.collection.immutable

import protocol._
import java.util.concurrent.TimeUnit
import pl.project13.scala.akka.raft.model.{Entry, ReplicatedLog, Term, LogIndexMap}
import pl.project13.scala.akka.raft.protocol.RaftStates._

trait RaftActor extends RaftStateMachine
  with Actor with LoggingFSM[RaftState, Metadata]
  with Follower with Candidate with Leader {

  type Command
  type Member = ActorRef

  private val config = context.system.settings.config

  private val HeartbeatTimerName = "heartbeat-timer"
  private val ElectionTimeoutTimerName = "election-timer"

  // todo rethink if needed
  var electionTimeoutDieOn = 0L

  // todo or move to Meta
  var replicatedLog = ReplicatedLog.empty[Command]

  private val minElectionTimeout = config.getDuration("akka.raft.election-timeout.min", TimeUnit.MILLISECONDS).millis
  private val maxElectionTimeout = config.getDuration("akka.raft.election-timeout.max", TimeUnit.MILLISECONDS).millis
  def nextElectionTimeout: FiniteDuration = randomElectionTimeout(
    from = minElectionTimeout,
    to = maxElectionTimeout
  )

  val heartbeatInterval: FiniteDuration = config.getDuration("akka.raft.heartbeat-interval", TimeUnit.MILLISECONDS).millis

  // todo or move to Meta
  var nextIndex = LogIndexMap.initialize(Set.empty, replicatedLog.lastIndex)
  // todo or move to Meta
  var matchIndex = LogIndexMap.initialize(Set.empty, -1)

  override def preStart() {
    val timeout = resetElectionTimeout()
    log.info("Starting new Raft member. Initial election timeout: " + timeout)
  }

  startWith(Follower, Meta.initial)

  when(Follower)(followerBehavior orElse clusterManagementBehavior)

  when(Candidate)(candidateBehavior orElse clusterManagementBehavior)

  when(Leader)(leaderBehavior orElse clusterManagementBehavior)

  /** Handles adding / removing raft members; Should be handled in every state */ // todo more tests around this
  lazy val clusterManagementBehavior: StateFunction = {
    case Event(RaftMemberAdded(newMember), m: Meta) =>
      val all = m.members + newMember
      log.info(s"Members changed, current: [size = ${all.size}] ${all.map(_.path.elements.last).mkString("[", ", ", "]")}, added: $newMember")

      // todo migration period initiated here, right?
      stay() using m.copy(members = all)

    case Event(RaftMemberRemoved(removed), m: Meta) =>
      val all = m.members - removed
      log.info(s"Members changed, current: [size = ${all.size}] ${all.map(_.path.elements.last).mkString("[", ", ", "]")}, removed: $removed")

      stay() using m.copy(members = all)
  }

  onTransition {
    case Follower -> Candidate =>
      self ! BeginElection
      resetElectionTimeout()

    case Candidate -> Leader =>
      self ! ElectedAsLeader()
      cancelElectionTimeout()

    case _ -> Follower =>
      resetElectionTimeout()
  }

  onTermination {
    case stop =>
      stopHeartbeat()
  }

  initialize() // akka internals; MUST be last call in constructor

  // helpers -----------------------------------------------------------------------------------------------------------

  def stopHeartbeat() {
    cancelTimer(HeartbeatTimerName)
  }

  def startHeartbeat(m: LeaderMeta) {
//  def startHeartbeat(currentTerm: Term, members: Set[ActorRef]) {
    sendHeartbeat(m)
    log.info(s"Starting hearbeat, with interval: $heartbeatInterval")
    setTimer(HeartbeatTimerName, SendHeartbeat, heartbeatInterval, repeat = true)
  }

  /** heartbeat is implemented as basically sending AppendEntry messages */
  def sendHeartbeat(m: LeaderMeta) {
    replicateLog(m)
  }

  def replicateLog(m: LeaderMeta) {
    m.others foreach { member =>
      // todo remove me
      log.info(s"""sending : ${AppendEntries(
              m.currentTerm,
              replicatedLog,
              fromIndex = nextIndex.valueFor(member),
              leaderCommitId = replicatedLog.committedIndex
            )} to $member""")

      member ! AppendEntries(
        m.currentTerm,
        replicatedLog,
        fromIndex = nextIndex.valueFor(member),
        leaderCommitId = replicatedLog.committedIndex
      )
    }
  }

  def cancelElectionTimeout() {
    cancelTimer(ElectionTimeoutTimerName)
  }

    def resetElectionTimeout(): FiniteDuration = {
    cancelTimer(ElectionTimeoutTimerName)

    val timeout = nextElectionTimeout
    val since = System.currentTimeMillis()
    log.info(s"Resetting election timeout: $timeout (since:$since)")

    electionTimeoutDieOn = since + timeout.toMillis
    setTimer(ElectionTimeoutTimerName, ElectionTimeout(since), timeout, repeat = false)

    timeout
  }

  private def randomElectionTimeout(from: FiniteDuration = 150.millis, to: FiniteDuration = 300.millis): FiniteDuration = {
    val fromMs = from.toMillis
    val toMs = to.toMillis
    require(toMs > fromMs, s"to ($to) must be greater than from ($from) in order to create valid election timeout.")

    (fromMs + Random.nextInt(toMs.toInt - fromMs.toInt)).millis
  }

  @inline private[raft] def electionTimeoutStillValid(since: Long) = {
    val stillValid = electionTimeoutDieOn < System.currentTimeMillis()

    if (stillValid)
      log.info(s"Timeout reached (since: $since, ago: ${System.currentTimeMillis() - since})")

    stillValid
  }

  // named state changes
  /** Start a new election */
  def beginElection(m: Meta) = {
    resetElectionTimeout()

    if (m.members.isEmpty) {
      // cluster is still discovering nodes, keep waiting
      goto(Follower) using m
    } else {
      goto(Candidate) using m.forNewElection forMax nextElectionTimeout
    }
  }

  /** Stop being the Leader */
  def stepDown(m: LeaderMeta) = goto(Follower) using m.forFollower

  /** Stay in current state and reset the election timeout */
  def stayAcceptingHeartbeat() = {
    resetElectionTimeout()
    stay()
  }



  def commitUntilLeadersIndex(m: Meta, msg: AppendEntries[Command]): ReplicatedLog[Command] = {
    val entries = replicatedLog.between(replicatedLog.committedIndex, msg.leaderCommitId)

    entries.foldLeft(replicatedLog) { case (repLog, entry) =>
      log.info(s"committing entry $entry on Follower, leader is committed until [${msg.leaderCommitId}]")
      apply(entry.command)

      repLog.commit(entry.index)
    }
  }

  def maybeCommitEntry(matchIndex: LogIndexMap, replicatedLog: ReplicatedLog[Command]): ReplicatedLog[Command] = {
    val indexOnMajority = matchIndex.indexOnMajority
    val willCommit = indexOnMajority > replicatedLog.committedIndex
    log.info(s"Majority of members have index: $indexOnMajority persisted. (Comitted index: ${replicatedLog.committedIndex}, will commit now: $willCommit)")

    if (willCommit) {
      val entries = replicatedLog.between(replicatedLog.committedIndex, indexOnMajority)
      log.info(s"Before commit; indexOnMajority:$indexOnMajority, replicatedLog.committedIndex: ${replicatedLog.committedIndex} => entries = $entries")

      entries foreach { entry =>
        log.info(s"Committing log at index: ${entry.index}; Applying command: ${entry.command}, will send result to client: ${entry.client}")

        val result = apply(entry.command)
        entry.client map { _ ! result }
      }

      replicatedLog.commit(indexOnMajority)
    } else {
      replicatedLog
    }
  }

  /** `true` if this follower is at `Term(2)`, yet the incoming term is `t > Term(3)` */
  def isInconsistentTerm(currentTerm: Term, term: Term): Boolean = term < currentTerm

  // sender aliases, for readability
  @inline def follower = sender()
  @inline def candidate = sender()
  @inline def leader = sender()

  @inline def voter = sender() // not explicitly a Raft role, but makes it nice to read
  // end of sender aliases
}



