package pl.project13.scala.akka.raft

import akka.actor.ActorRef

import model._
import protocol._
import config.RaftConfig

private[raft] trait Leader {
  this: RaftActor =>

  protected def raftConfig: RaftConfig

  private val HeartbeatTimerName = "heartbeat-timer"

  val leaderBehavior: StateFunction = {
    case Event(msg @ BeginAsLeader(term, _), m: Meta) =>
      if (raftConfig.publishTestingEvents) context.system.eventStream.publish(msg)
      log.info("Became leader for {}", m.currentTerm)
      initializeLeaderState(m.config.members)
      startHeartbeat(m)
      stay()

    case Event(SendHeartbeat, m: Meta) =>
      sendHeartbeat(m)
      stay()

    // already won election, but votes may still be coming in
    case Event(_: ElectionMessage, _) =>
      stay()

    // client request
    case Event(ClientMessage(client, cmd: Command), m: Meta) =>
      log.info("Appending command: [{}] from {} to replicated log...", cmd, client)

      val entry = Entry(cmd, m.currentTerm, replicatedLog.nextIndex, Some(client))

      log.debug("adding to log: {}", entry)
      replicatedLog += entry
      matchIndex.put(m.clusterSelf, entry.index)
      log.debug("log status = {}", replicatedLog)

      val meta = maybeUpdateConfiguration(m, entry.command)
      replicateLog(meta)

      if (meta.config.isPartOfNewConfiguration(m.clusterSelf)) {
        stay() applying WithNewConfigEvent(config = meta.config)
      } else {
        goto(Follower) applying GoToFollowerEvent()
      }

    // rogue Leader handling
    case Event(append: AppendEntries[Command], m: Meta) if append.term > m.currentTerm =>
      log.info("Leader (@ {}) got AppendEntries from fresher Leader (@ {}), will step down and the Leader will keep being: {}", m.currentTerm, append.term, sender())
      stopHeartbeat()
      stepDown(m)

    case Event(append: AppendEntries[Command], m: Meta) if append.term <= m.currentTerm =>
      log.warning("Leader (@ {}) got AppendEntries from rogue Leader ({} @ {}); It's not fresher than self. Will send entries, to force it to step down.", m.currentTerm, sender(), append.term)
      sendEntries(sender(), m)
      stay()
    // end of rogue Leader handling

    // append entries response handling

    case Event(AppendRejected(term), m: Meta) if term > m.currentTerm =>
      stopHeartbeat()
      stepDown(m) // since there seems to be another leader!

    case Event(msg: AppendRejected, m: Meta) if msg.term == m.currentTerm =>
      registerAppendRejected(follower(), msg, m)

    case Event(msg: AppendSuccessful, m: Meta) if msg.term == m.currentTerm =>
      registerAppendSuccessful(follower(), msg, m)

    case Event(RequestConfiguration, m: Meta) =>
      sender() ! ChangeConfiguration(m.config)
      stay()

    case Event(AskForState, _) =>
      sender() ! IAmInState(Leader)
      stay()
  }

  def initializeLeaderState(members: Set[ActorRef]) {
    log.info("Preparing nextIndex and matchIndex table for followers, init all to: replicatedLog.lastIndex = {}", replicatedLog.lastIndex)
    nextIndex = LogIndexMap.initialize(members, replicatedLog.lastIndex)
    matchIndex = LogIndexMap.initialize(members, -1)
  }

  def sendEntries(follower: ActorRef, m: Meta) {
    follower ! AppendEntries(
      m.currentTerm,
      replicatedLog,
      fromIndex = nextIndex.valueFor(follower),
      leaderCommitIdx = replicatedLog.committedIndex
    )
  }

  def stopHeartbeat() {
    cancelTimer(HeartbeatTimerName)
  }

  def startHeartbeat(m: Meta) {
    sendHeartbeat(m)
    log.info("Starting hearbeat, with interval: {}", heartbeatInterval)
    setTimer(HeartbeatTimerName, SendHeartbeat, heartbeatInterval, repeat = true)
  }

  /** heartbeat is implemented as basically sending AppendEntry messages */
  def sendHeartbeat(m: Meta) {
    replicateLog(m)
  }

  def replicateLog(m: Meta) {
    m.membersExceptSelf foreach { member =>
      // todo remove me
//      log.info("sending: {} to {}", AppendEntries(m.currentTerm, replicatedLog, fromIndex = nextIndex.valueFor(member), leaderCommitId = replicatedLog.committedIndex), member)

      member ! AppendEntries(
        m.currentTerm,
        replicatedLog,
        fromIndex = nextIndex.valueFor(member),
        leaderCommitIdx = replicatedLog.committedIndex
      )
    }
  }

  def registerAppendRejected(member: ActorRef, msg: AppendRejected, m: Meta) = {
    val AppendRejected(followerTerm) = msg

    log.info("Follower {} rejected write: {}, back out the first index in this term and retry", follower(), followerTerm)

    if (nextIndex.valueFor(follower()) > 1) {
      nextIndex.decrementFor(follower())
    }

//    todo think if we send here or keep in heartbeat
    sendEntries(follower(), m)

    stay()
  }

  def registerAppendSuccessful(member: ActorRef, msg: AppendSuccessful, m: Meta) = {
    val AppendSuccessful(followerTerm, followerIndex) = msg

    log.info("Follower {} took write in term: {}, next index was: {}", follower(), followerTerm, nextIndex.valueFor(follower()))

    // update our tables for this member
    assert(followerIndex <= replicatedLog.lastIndex)
    nextIndex.put(follower(), followerIndex + 1)
    matchIndex.putIfGreater(follower(), followerIndex)

    replicatedLog = maybeCommitEntry(m, matchIndex, replicatedLog)

    stay()
  }

  def maybeCommitEntry(m: Meta, matchIndex: LogIndexMap, replicatedLog: ReplicatedLog[Command]): ReplicatedLog[Command] = {
    val indexOnMajority = matchIndex.consensusForIndex(m.config)
    val willCommit = indexOnMajority > replicatedLog.committedIndex

    if (willCommit) {
      log.info("Consensus for persisted index: {}. (Comitted index: {}, will commit now: {})", indexOnMajority, replicatedLog.committedIndex, willCommit)

      val entries = replicatedLog.between(replicatedLog.committedIndex, indexOnMajority)

      entries foreach { entry =>
        handleCommitIfSpecialEntry.applyOrElse(entry, default = handleNormalEntry)

        if (raftConfig.publishTestingEvents)
          context.system.eventStream.publish(EntryCommitted(entry.index, m.clusterSelf))
      }

      replicatedLog.commit(indexOnMajority)
    } else {
      replicatedLog
    }
  }

  /**
   * Used for handling special messages, such as ''new Configuration'' or a ''Snapshot entry'' being comitted.
   *
   * Note that special log entries will NOT be propagated to the client state machine.
   */
  private val handleCommitIfSpecialEntry: PartialFunction[Entry[Command], Unit] = {
    case Entry(jointConfig: JointConsensusClusterConfiguration, _, _, _) =>
      self ! ClientMessage(self, jointConfig.transitionToStable) // will cause comitting of only "new" config

    case Entry(stableConfig: StableClusterConfiguration, _, _, _) =>
      // simply ignore, once this message is in our log we started using the new configuration anyway,
      // there's no need to apply this message onto the client state machine.
  }

  private val handleNormalEntry: PartialFunction[Any, Unit] = {
    case entry: Entry[Command] =>
      log.info("Committing log at index: {}; Applying command: {}, will send result to client: {}", entry.index, entry.command, entry.client)
      val result = apply(entry.command) // todo what if we apply a message the actor didnt understand? should fail "nicely"
      entry.client foreach { _ ! result }
  }

  /**
   * Configurations must be used by each node right away when they get appended to their logs (doesn't matter if not committed).
   * This method updates the Meta object if a configuration change is discovered.
   */
  def maybeUpdateConfiguration(meta: Meta, entry: Command): Meta = entry match {
    case newConfig: ClusterConfiguration if newConfig.isNewerThan(meta.config) =>
      log.info("Appended new configuration, will start using it now: {}", newConfig)
      meta.withConfig(newConfig)

    case _ =>
      meta
  }

}
