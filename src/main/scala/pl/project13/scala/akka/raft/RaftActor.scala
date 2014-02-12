package pl.project13.scala.akka.raft

import akka.actor.{Actor, ActorRef, LoggingFSM}
import scala.concurrent.duration._

import model._
import protocol._
import scala.concurrent.forkjoin.ThreadLocalRandom
import pl.project13.scala.akka.raft.compaction.LogCompactionExtension
import pl.project13.scala.akka.raft.config.RaftConfiguration

abstract class RaftActor extends Actor with LoggingFSM[RaftState, Metadata]
  with ReplicatedStateMachine
  with Follower with Candidate with Leader with SharedBehaviors {

  type Command

  protected val raftConfig = RaftConfiguration(context.system)

  protected val logCompaction = LogCompactionExtension(context.system)

  private val ElectionTimeoutTimerName = "election-timer"

  /**
   * Used in order to avoid starting elections when we process an timeout event (from the election-timer),
   * which is immediatly followed by a message from the Leader.
   * This is because timeout handling is just a plain old message (no priority implemented there).
   *
   * This means our election reaction to timeouts is relaxed a bit - yes, but this should be a good thing.
   */
  var electionDeadline: Deadline = 0.seconds.fromNow


  // raft member state ---------------------

  var replicatedLog = ReplicatedLog.empty[Command](raftConfig.defaultAppendEntriesBatchSize)

  var nextIndex = LogIndexMap.initialize(Set.empty, replicatedLog.lastIndex)

  var matchIndex = LogIndexMap.initialize(Set.empty, -1)

  // end of raft member state --------------

  val heartbeatInterval: FiniteDuration = raftConfig.heartbeatInterval

  def nextElectionDeadline(): Deadline = randomElectionTimeout(
    from = raftConfig.electionTimeoutMin,
    to = raftConfig.electionTimeoutMax
  ).fromNow

  override def preStart() {
    log.info("Starting new Raft member, will wait for raft cluster configuration...")
  }

  startWith(Init, Meta.initial)

  when(Init)(initialConfigurationBehavior)

  when(Follower)(followerBehavior orElse snapshottingBehavior orElse clusterManagementBehavior)

  when(Candidate)(candidateBehavior orElse snapshottingBehavior orElse clusterManagementBehavior)

  when(Leader)(leaderBehavior orElse snapshottingBehavior orElse clusterManagementBehavior)

  onTransition {
    case Init -> Follower if stateData.clusterSelf != self =>
      log.info("Cluster self != self => Running clustered via a proxy.")
      resetElectionDeadline()

    case Follower -> Candidate =>
      self ! BeginElection
      resetElectionDeadline()

    case Candidate -> Leader =>
      self ! ElectedAsLeader
      cancelElectionDeadline()

    case _ -> Follower =>
      resetElectionDeadline()
  }

  onTermination {
    case stop =>
      stopHeartbeat()
  }

  initialize() // akka internals; MUST be last call in constructor

  // helpers -----------------------------------------------------------------------------------------------------------

  def cancelElectionDeadline() {
    cancelTimer(ElectionTimeoutTimerName)
  }

  def resetElectionDeadline(): Deadline = {
    cancelTimer(ElectionTimeoutTimerName)

    electionDeadline = nextElectionDeadline()
    log.debug("Resetting election timeout: {}", electionDeadline)

    setTimer(ElectionTimeoutTimerName, ElectionTimeout, electionDeadline.timeLeft, repeat = false)

    electionDeadline
  }

  private def randomElectionTimeout(from: FiniteDuration, to: FiniteDuration): FiniteDuration = {
    val fromMs = from.toMillis
    val toMs = to.toMillis
    require(toMs > fromMs, s"to ($to) must be greater than from ($from) in order to create valid election timeout.")

    (fromMs + ThreadLocalRandom.current().nextInt(toMs.toInt - fromMs.toInt)).millis
  }

  // named state changes --------------------------------------------

  /** Start a new election */
  def beginElection(m: Meta) = {
    resetElectionDeadline()

    if (m.config.members.isEmpty) {
      // cluster is still discovering nodes, keep waiting
      goto(Follower) using m
    } else {
      goto(Candidate) using m.forNewElection forMax nextElectionDeadline().timeLeft
    }
  }

  /** Stop being the Leader */
  def stepDown(m: LeaderMeta) = {
    goto(Follower) using m.forFollower
  }

  /** Stay in current state and reset the election timeout */
  def acceptHeartbeat() = {
    resetElectionDeadline()
    stay()
  }

  // end of named state changes -------------------------------------

  /** `true` if this follower is at `Term(2)`, yet the incoming term is `t > Term(3)` */
  def isInconsistentTerm(currentTerm: Term, term: Term): Boolean = term < currentTerm

  // sender aliases, for readability
  @inline def follower() = sender()
  @inline def candidate() = sender()
  @inline def leader() = sender()

  @inline def voter() = sender() // not explicitly a Raft role, but makes it nice to read
  // end of sender aliases

}



