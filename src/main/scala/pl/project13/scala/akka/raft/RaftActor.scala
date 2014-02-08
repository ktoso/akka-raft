package pl.project13.scala.akka.raft

import akka.actor.{Actor, ActorRef, LoggingFSM}
import scala.concurrent.duration._

import model._
import protocol._
import scala.concurrent.forkjoin.ThreadLocalRandom

abstract class RaftActor extends Actor with LoggingFSM[RaftState, Metadata]
  with ReplicatedStateMachine
  with Follower with Candidate with Leader {

  type Command
  type Member = ActorRef

  private val config = context.system.settings.config

  protected val raftConfig = RaftConfiguration(config)

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

  when(Init)(awaitInitialConfigurationBehavior)

  when(Follower)(followerBehavior orElse clusterManagementBehavior)

  when(Candidate)(candidateBehavior orElse clusterManagementBehavior)

  when(Leader)(leaderBehavior orElse clusterManagementBehavior)

  /** Waits for initial cluster configuration. Step needed before we can start voting for a Leader. */
  lazy val awaitInitialConfigurationBehavior: StateFunction = {
    case Event(ChangeConfiguration(initialConfig), m: Meta) =>
      log.info(s"Applying initial raft cluster configuration. Consists of [{}] nodes: {}",
        initialConfig.members.size,
        initialConfig.members.map(_.path.elements.last).mkString("{", ", ", "}"))

      val timeout = resetElectionTimeout()
      log.info("Finished init of new Raft member, becoming Follower. Initial election timeout: " + timeout)
      goto(Follower) using m.copy(config = initialConfig)

    case Event(msg: AppendEntries[Command], m: Meta) =>
      log.info("Got message from a Leader, but am in Init state. Will ask for it's configuration and join Raft cluster.")
      leader() ! RequestConfiguration
      stay()
  }

  /** Handles adding / removing raft members; Should be handled in every state */
  lazy val clusterManagementBehavior: StateFunction = {
    // enter joint consensus phase of configuration comitting
    case Event(ChangeConfiguration(newConfiguration), m: Metadata) =>
      val transitioningConfig = m.config transitionTo newConfiguration

      log.info(s"Starting transition to new Configuration, " +
        s"old [size: ${m.config.members.size}]: ${simpleNames(m.config.members)}, " +
        s"migrating to [size: ${transitioningConfig.transitionToStable.members.size}]: $transitioningConfig")

      // configuration change goes over the same process as log appending
      // here we initiate the 1st phase - committing the "joint consensus config", which includes all nodes from these configs
      // the 2nd phase is initiated upon committing of this entry to the log.
      // Once the new config is committed, nodes that are not included can step-down
      self ! ClientMessage(self, transitioningConfig)

      stay()
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

  private def randomElectionTimeout(from: FiniteDuration = 150.millis, to: FiniteDuration = 300.millis): FiniteDuration = {
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



