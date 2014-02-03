package pl.project13.scala.akka.raft

import akka.actor.ActorRef

import pl.project13.scala.akka.raft.cluster.ClusterProtocol.{IAmInState, AskForState}

import model._
import protocol._

private[raft] trait Leader {
  this: RaftActor =>

  private val HeartbeatTimerName = "heartbeat-timer"

  val leaderBehavior: StateFunction = {
    case Event(ElectedAsLeader(), m: LeaderMeta) =>
      log.info(bold(s"Became leader for ${m.currentTerm}"))
      initializeLeaderState(m.config.members)
      startHeartbeat(m)
      stay()

    case Event(SendHeartbeat, m: LeaderMeta) =>
      sendHeartbeat(m)
      stay()

    // already won election, but votes may still be coming in
    case Event(_: ElectionMessage, _) =>
      stay()

    // client request
    case Event(ClientMessage(client, cmd: Command), m: LeaderMeta) =>
      log.info(s"Appending command: [${bold(cmd)}] from $client to replicated log...")

      val entry = Entry(cmd, m.currentTerm, replicatedLog.nextIndex, Some(client))

      log.info(s"adding to log: $entry")
      replicatedLog += entry
      log.info(s"log status = $replicatedLog")

      replicateLog(m)

      stay()

    case Event(AppendRejected(term, index), m: LeaderMeta) if term > m.currentTerm =>
      stopHeartbeat()
      stepDown(m) // since there seems to be another leader!

    case Event(msg: AppendRejected, m: LeaderMeta) =>
      registerAppendRejected(follower(), msg, m)

    case Event(msg: AppendSuccessful, m: LeaderMeta) =>
      registerAppendSuccessful(follower(), msg, m)

    case Event(RequestConfiguration, m: LeaderMeta) =>
      sender() ! ChangeConfiguration(m.config)
      stay()

    case Event(AskForState, _) =>
      sender() ! IAmInState(Leader)
      stay()
  }

  def initializeLeaderState(members: Set[Member]) {
    log.info(s"Preparing nextIndex and matchIndex table for followers, init all to: replicatedLog.lastIndex = ${replicatedLog.lastIndex}")
    nextIndex = LogIndexMap.initialize(members, replicatedLog.lastIndex)
    matchIndex = LogIndexMap.initialize(members, -1)
  }

  def sendEntries(follower: Member, m: LeaderMeta) {
    follower ! AppendEntries(
      m.currentTerm,
      replicatedLog,
      fromIndex = nextIndex.valueFor(follower),
      leaderCommitId = replicatedLog.committedIndex
    )
  }

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
              fromIndex = nextIndex.valueFor(member), // todo shouldnt we increase here?
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

  def registerAppendRejected(member: ActorRef, msg: AppendRejected, m: LeaderMeta) = {
    val AppendRejected(followerTerm, followerIndex) = msg

    log.info(s"Follower ${follower()} rejected write: $followerTerm @ $followerIndex, back out the first index in this term and retry")
    log.info(s"Leader log state: " + replicatedLog.entries)

    nextIndex.putIfSmaller(follower(), followerIndex)

//    todo think if we send here or keep in heartbeat
    sendEntries(follower(), m)

    stay()
  }
  def registerAppendSuccessful(member: ActorRef, msg: AppendSuccessful, m: LeaderMeta) = {
    log.info(s"Follower ${follower()} accepted write")
    val AppendSuccessful(followerTerm, followerIndex) = msg

    log.info(s"Follower ${follower()} took write in term: $followerTerm, index: ${nextIndex.valueFor(follower())}")

    // update our tables for this member
    nextIndex.put(follower(), followerIndex + 1)
    matchIndex.putIfGreater(follower(), nextIndex.valueFor(follower()))

    // todo horrible api of maybeCommitEntry
    val (repLog, meta) = maybeCommitEntry(m, matchIndex, replicatedLog)
    replicatedLog = repLog

    stay() using meta
  }

  // todo make replicated log party of meta, then we'll have less trouble here
  def maybeCommitEntry(m: LeaderMeta, matchIndex: LogIndexMap, replicatedLog: ReplicatedLog[Command]): (ReplicatedLog[Command], LeaderMeta) = {
    var meta = m // might change

    val indexOnMajority = matchIndex.indexOnMajority
    val willCommit = indexOnMajority > replicatedLog.committedIndex
    log.info(s"Majority of members have index: $indexOnMajority persisted. (Comitted index: ${replicatedLog.committedIndex}, will commit now: $willCommit)")

    // each commit may have caused the membership configuration to change for example
    val handleNormalEntry: PartialFunction[Any, LeaderMeta] = {
      case entry: Entry[Command] =>
        log.info(s"Committing log at index: ${entry.index}; Applying command: ${entry.command}, will send result to client: ${entry.client}")
        val result = apply(entry.command)
        entry.client foreach { _ ! result }
        meta
    }

    if (willCommit) {
      val entries = replicatedLog.between(replicatedLog.committedIndex, indexOnMajority)
//      log.info(s"Before commit; indexOnMajority:$indexOnMajority, replicatedLog.committedIndex: ${replicatedLog.committedIndex} => entries = $entries")

      entries foreach { entry =>
        // handle special || handle normal, explicit types because compiler paniced
        val handleAsSpecial = handleCommitIfSpecialEntry(meta)
        meta = handleAsSpecial.applyOrElse(entry, default = handleNormalEntry)
      }

      replicatedLog.commit(indexOnMajority) -> meta
    } else {
      replicatedLog -> m
    }
  }

  /**
   * Used for handling special messages, such as ''new Configuration'' or a ''Snapshot entry'' being comitted.
   *
   * Note that special log entries will NOT be propagated to the client state machine.
   *
   * @todo this is quite hacky... because the message stored in the log is not `<: Cmnd`, this of a better way maybe?
   */
  private def handleCommitIfSpecialEntry(m: LeaderMeta): PartialFunction[Entry[Command], LeaderMeta] = {
    case Entry(jointConfig: JointConsensusRaftConfiguration, _, _, _) =>
      log.info("JointConsensusRaftConfiguration committed on Leader, entering: {}, proceeding with trying to commit new config", jointConfig)

      self ! ClientMessage(self, jointConfig.transitionToStable) // will cause comitting of only "new" config

      m.copy(config = jointConfig)

    case Entry(stableConfig: StableRaftConfiguration, _, _, _) =>
      log.info("StableRaftConfiguration committed on Leader, finishing transition phase, stable with config: {}", stableConfig)

      if (!stableConfig.isPartOfNewConfiguration(self)) {
        log.info("Committed new configuration, this member is not part of it - stopping self.")
        context stop self
      }

      m.copy(config = stableConfig)
  }

  // todo remove me
  private def bold(msg: Any): String = Console.BOLD + msg.toString + Console.RESET

}