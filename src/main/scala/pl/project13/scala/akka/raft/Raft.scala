package pl.project13.scala.akka.raft

import akka.actor.{Actor, ActorRef, LoggingFSM}
import scala.concurrent.duration._
import scala.util.Random
import scala.concurrent.Future
import scala.collection.immutable

import protocol._
import java.util.concurrent.TimeUnit


trait Raft extends LoggingFSM[RaftState, Metadata] {
  this: Actor =>

  import context.dispatcher

  type Command <: AnyRef
  type Commands = immutable.Seq[Command]
  type Member = ActorRef

  private val config = context.system.settings.config

  val HeartbeatTimerName = "heartbeat-timer"
  val ElectionTimeoutTimerName = "election-timer"

  // user-land API -----------------------------------------------------------------------------------------------------
  /** Called when a command is determined by Raft to be safe to apply */
  def apply(command: Command): Unit
  // end of user-land API -----------------------------------------------------------------------------------------------------


  // must be included in leader's heartbeat
  def highestCommittedTermNr = Term.Zero

  var replicatedLog = ReplicatedLog.empty[Command]

  val minElectionTimeout = config.getDuration("akka.raft.election-timeout.min", TimeUnit.MILLISECONDS).millis
  val maxElectionTimeout = config.getDuration("akka.raft.election-timeout.max", TimeUnit.MILLISECONDS).millis
  def nextElectionTimeout: FiniteDuration = randomElectionTimeout(
    from = minElectionTimeout, to = maxElectionTimeout 
  )

  val heartbeatInterval: FiniteDuration = config.getDuration("akka.raft.heartbeat-interval", TimeUnit.MILLISECONDS).millis

  var nextIndex = LogIndexMap.initialize(Vector.empty, replicatedLog.lastIndex)
  var matchIndex = LogIndexMap.initialize(Vector.empty, 0)

  override def preStart() {
    val timeout = resetElectionTimeout()
    log.debug("Initial election timeout: " + timeout)
  }

  startWith(Follower, Meta.initial)

  when(Follower) {
    
    // election
    case Event(RequestVote(term, candidate, lastLogTerm, lastLogIndex), m: Meta) if m.canVote && term > m.currentTerm =>
      log.info(s"Voting for $candidate in $term")
      sender ! Vote(m.currentTerm)
      stay()

    case Event(RequestVote(term, candidateId, lastLogTerm, lastLogIndex), m: Meta) =>
      sender ! Reject(m.currentTerm)
      stay()
      
    // end of election

    // append entries
    case Event(AppendEntries(term, leader, prevLogIndex, prevLogTerm, entries: Entries[Command]), m: Meta)
      if replicatedLog.isConsistentWith(prevLogIndex, prevLogTerm) =>
      stayAcceptingHeartbeat() using m.copy(currentTerm = term)

    // logs don't match, don't append but refresh timer
    case Event(AppendEntries(term, leader, prevLogIndex, prevLogTerm, entries: Entries[Command]), m: Meta) =>
      stayAcceptingHeartbeat()

    // need to start an election
    case Event(ElectionTimeout, m: Meta) =>
      log.info(s"Election timeout reached")
      beginElection(m)
  }

  when(Candidate) {
    // election
    case Event(BeginElection, m: ElectionMeta) =>
      log.debug(s"Initializing election for ${m.currentTerm}")

      val request = RequestVote(m.currentTerm, self, replicatedLog.lastTerm, replicatedLog.lastIndex)
      m.membersExceptSelf foreach { _ ! request }

      stay() using m.incTerm.incVote

    case Event(RequestVote(term, candidate, lastLogTerm, lastLogIndex), m: ElectionMeta) if term < m.currentTerm =>
      sender ! Reject
      stay()

    case Event(RequestVote(term, candidate, lastLogTerm, lastLogIndex), m: ElectionMeta) if m.canVote =>
      sender ! Vote(m.currentTerm)
      stay()

    case Event(Vote(term), data: ElectionMeta) if data.incVote.hasMajority =>
      log.info(s"Received enough votes to be majority (${data.incVote.votesReceived} of ${data.members.size})")
      goto(Leader) using data.forLeader

    case Event(Vote(term), data: ElectionMeta) =>
      log.info(s"Received vote from $voter, now up to ${data.incVote.votesReceived}")
      stay() using data.incVote

    case Event(Reject(term), m: ElectionMeta) =>
      log.debug(s"Rejected vote by $voter, in $term")
      stay()

    // end of election

    // log appending -- todo step down and handle in Follower
    case Event(AppendEntries(term, leader, prevLogIndex, prevLogTerm, entries), m) if term >= m.currentTerm =>
      log.info("Got valid AppendEntries, falling back to Follower state and replaying message")
      self ! AppendEntries(term, leader, prevLogIndex, prevLogTerm, entries)

      goto(Follower)

    // todo should act differently
    case Event(AppendEntries(term, leader, prevLogIndex, prevLogTerm, entries: Entries[Command]), m)
        if isInconsistentTerm(m.currentTerm, term) =>
      log.info(s"Got AppendEntries from stale $term, from $leader, not adding entries. AppendSuccessful.")
      sender ! AppendSuccessful(m.currentTerm)

      goto(Follower)

    // ending election due to timeout
    case Event(StateTimeout, data: ElectionMeta) =>
      log.debug("Voting timeout, starting a new election...")
      self ! BeginElection
      stay() using data.forFollower.forNewElection
  }

  when(Leader) {
    case Event(ElectedAsLeader(), m: LeaderMeta) =>
      log.info("Became leader!")
      prepareEachFollowersNextIndex(m.others)
      startHeartbeat(m.currentTerm, m.others)
      stay()

    case Event(SendHeartbeat, m: LeaderMeta) =>
      sendHeartbeat(m.currentTerm, m.others)
      stay()

    case Event(AppendSuccessful(term), _) =>
      // todo probably not needed
      stay()

    case Event(AppendRejected(term), m: LeaderMeta) if term > m.currentTerm =>
      stopHeartbeat()
      stepDown(m) // since there seems to be another leader!

    case Event(AppendRejected(term), m: LeaderMeta) =>
      nextIndex = nextIndex.decrementFor(follower)
      val logIndexToSend = nextIndex.valueFor(follower)

      // todo continue sending to follower, to make it catch up
      // todo should include term a command came from, right?
      follower ! AppendEntries(m.currentTerm, self, replicatedLog.lastIndex, replicatedLog.lastTerm, replicatedLog.entriesFrom(logIndexToSend))

      stay()
  }

  whenUnhandled {
    case Event(MembersChanged(newMembers), data: Meta) =>
      log.info(s"Members changed, current: ${data.members}, updating to: $newMembers")
      // todo migration period initiated here, right?
      stay() using data.copy(members = newMembers)
  }

  onTransition {
    case Follower -> Candidate =>
      self ! BeginElection
      resetElectionTimeout()

    case Leader -> Follower =>
      resetElectionTimeout()

    case Candidate -> Leader =>
      self ! ElectedAsLeader()
      cancelElectionTimeout()
  }

  onTermination {
    case stop =>
      stopHeartbeat()
  }

  // helpers -----------------------------------------------------------------------------------------------------------

  def logIndex: Int = replicatedLog.lastIndex

  def prepareEachFollowersNextIndex(followers: immutable.Seq[ActorRef]) {
    nextIndex = LogIndexMap.initialize(followers, replicatedLog.lastIndex)
  }

  def stopHeartbeat() {
    cancelTimer(HeartbeatTimerName)
  }

  def startHeartbeat(currentTerm: Term, members: Vector[ActorRef]) {
    sendHeartbeat(currentTerm, members)
    log.info(s"Starting hearbeat, with interval: $heartbeatInterval")
    setTimer(HeartbeatTimerName, SendHeartbeat, heartbeatInterval, repeat = true)
  }

  def sendHeartbeat(currentTerm: Term, members: Vector[ActorRef]) {
    members foreach { _ ! AppendEntries(currentTerm, self, replicatedLog.lastIndex, replicatedLog.lastTerm, Nil) }
  }
  
  def cancelElectionTimeout() {
    cancelTimer(ElectionTimeoutTimerName)
  }

    def resetElectionTimeout(): FiniteDuration = {
    cancelTimer(ElectionTimeoutTimerName)

    val timeout = nextElectionTimeout
//    log.debug("Resetting election timeout: " + timeout)
    setTimer(ElectionTimeoutTimerName, ElectionTimeout, timeout, repeat = false)

    timeout
  }

  def appendToLog(x: Any): Future[Unit] = {
    // todo send to all, await safe write
    ???
    Future()
  }

  def randomElectionTimeout(from: FiniteDuration = 150.millis, to: FiniteDuration = 300.millis): FiniteDuration = {
    val fromMs = from.toMillis
    val toMs = to.toMillis
    require(toMs > fromMs, s"to ($to) must be greater than from ($from) in order to create valid election timeout.")

    (fromMs + Random.nextInt(toMs.toInt - fromMs.toInt)).millis
  }

  // named state changes
  /** Start a new election */
  def beginElection(m: Meta) = {
    resetElectionTimeout()
    goto(Candidate) using m.forNewElection
  }

  /** Stop being the Leader */
  def stepDown(m: LeaderMeta) = goto(Follower) using m.forFollower

  /** Stay in current state and reset the election timeout */
  def stayAcceptingHeartbeat() = {
    resetElectionTimeout()
    stay()
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
