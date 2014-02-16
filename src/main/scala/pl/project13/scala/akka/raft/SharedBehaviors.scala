package pl.project13.scala.akka.raft

import protocol._
import akka.actor.ActorRef
import pl.project13.scala.akka.raft.model.{RaftSnapshot, RaftSnapshotMetadata}

trait SharedBehaviors {
  this: RaftActor =>

  private[raft] implicit val raftDispatcher = context.system.dispatchers.lookup("raft-dispatcher")

  /** Used when a client contacts this Follower, instead of the Leader; so we redirect him to the Leader. */
  var recentlyContactedByLeader: Option[ActorRef] = None

  /** Waits for initial cluster configuration. Step needed before we can start voting for a Leader. */
  private[raft] lazy val initialConfigurationBehavior: StateFunction = {

    case Event(AssignClusterSelf(clusterSelf), m: Meta) =>
      log.info("Registered proxy actor, will expose clusterSelf as: {}", clusterSelf)
      stay() using m.copy(clusterSelf = clusterSelf)


    case Event(ChangeConfiguration(initialConfig), m: Meta) =>
      log.info("Applying initial raft cluster configuration. Consists of [{}] nodes: {}",
        initialConfig.members.size,
        initialConfig.members.map(_.path.elements.last).mkString("{", ", ", "}"))

      val deadline = resetElectionDeadline()
      log.info("Finished init of new Raft member, becoming Follower. Initial election deadline: {}", deadline)
      goto(Follower) using m.copy(config = initialConfig)


    case Event(msg: AppendEntries[Command], m: Meta) =>
      log.info("Got AppendEntries from a Leader, but am in Init state. Will ask for it's configuration and join Raft cluster.")
      leader() ! RequestConfiguration
      stay()


    // handle initial discovery of nodes, as opposed to initialization via `initialConfig`
    case Event(added: RaftMemberAdded, m: Meta) =>
      val newMembers = m.members + added.member
      
      val initialConfig = ClusterConfiguration(newMembers)
      
      if (added.keepInitUntil <= newMembers.size) {
        log.info("Discovered the required min. of {} raft cluster members, becoming Follower.", added.keepInitUntil)
        goto(Follower) using m.copy(config = initialConfig)
      } else {
        // keep waiting for others to be discovered
        log.info("Up to {} discovered raft cluster members, still waiting in Init until {} discovered.", newMembers.size, added.keepInitUntil)
        stay() using m.copy(config = initialConfig)
      }

    case Event(removed: RaftMemberRemoved, m: Meta) =>
      val newMembers = m.config.members - removed.member
            
      val waitingConfig = ClusterConfiguration(newMembers)
      
      // keep waiting for others to be discovered
      log.debug("Removed one member, until now discovered {} raft cluster members, still waiting in Init until {} discovered.", newMembers.size, removed.keepInitUntil)
      stay() using m.copy(config = waitingConfig)
  }

  /** Handles adding / removing raft members; Should be handled in every state */
  private[raft] lazy val clusterManagementBehavior: StateFunction = {

    case Event(WhoIsTheLeader, m: Metadata) =>
      stateName match {
        case Follower  => recentlyContactedByLeader foreach { _ forward WhoIsTheLeader } // needed in order to expose only "clusterSelfs"
        case Leader    => sender() ! LeaderIs(Some(m.clusterSelf), None)
        case _         => sender() ! LeaderIs(None, None)
      }
      stay()

    // enter joint consensus phase of configuration comitting
    case Event(ChangeConfiguration(newConfiguration), m: Metadata) =>
      val transitioningConfig = m.config transitionTo newConfiguration

      val configChangeWouldBeNoop =
        transitioningConfig.transitionToStable.members == m.config.members

      if (configChangeWouldBeNoop) {
        // no transition needed, the configuration change message was out of date
      } else {

        log.info("Starting transition to new Configuration, old [size: {}]: {}, migrating to [size: {}]: {}",
          m.config.members.size, simpleNames(m.config.members),
          transitioningConfig.transitionToStable.members.size, transitioningConfig)

        // configuration change goes over the same process as log appending
        // here we initiate the 1st phase - committing the "joint consensus config", which includes all nodes from these configs
        // the 2nd phase is initiated upon committing of this entry to the log.
        // Once the new config is committed, nodes that are not included can step-down
        m.clusterSelf ! ClientMessage(m.clusterSelf, transitioningConfig) // todo unclean?
      }

      stay()

    // todo handle RaftMember{Added / Removed} here? Think if not duplicating mechanisms though.
  }

  private[raft] lazy val snapshottingBehavior: StateFunction = {
    case Event(InitLogSnapshot, m: Metadata) =>
      val committedIndex = replicatedLog.committedIndex
      val meta = RaftSnapshotMetadata(replicatedLog.termAt(committedIndex), committedIndex, m.config)
      log.info("Initializing snapshotting of replicated log up to: {}", meta)

      val snapshotFuture = prepareSnapshot(meta)

      snapshotFuture onSuccess {
        case Some(snap) =>
          log.info("Successfuly prepared snapshot for {}, compacting log now...", meta)
          compactLogWith(snap)

        case None =>
          log.debug("No snapshot data obtained, skipping snapshotting...")
      }

      snapshotFuture onFailure {
        case ex: Throwable =>
          log.error("Unable to prepare snapshot!", ex)
      }

      stay()

    // logically only a Follower should take such write, keeping handling in SharedBehaviors though, as it's more nicely grouped here - by feature
    case Event(install: InstallSnapshot, m: Metadata) if stateName == Follower =>
      log.info("Got snapshot from {}, is for: {}", leader(), install.snapshot.meta)

      apply(install)
      compactLogWith(install.snapshot)

      stay()
  }

  /**
   * Compacts this member's replicated log.
   *
   * Each member executes replication independently; If a Follower requests writes of data older than the last snapshot
   * in a Leader's replicated log, the leader will issue an [[pl.project13.scala.akka.raft.protocol.RaftProtocol.InstallSnapshot]] command,
   * thus even with data loss (we drop past data), consistency can be achieved even with Followers that lag behind the current snapshot.
   *
   * Implementations of snapshotting can be provided as extensions,
   * see [[pl.project13.scala.akka.raft.compaction.LogCompactionSupport]].
   */
  // todo rethink flow here, not sure if we need such level of configurability (of different compactors...)
  private def compactLogWith(snapshot: RaftSnapshot) {
    val compactedLog = logCompaction.compact(replicatedLog, snapshot)

    if (raftConfig.publishTestingEvents)
      context.system.eventStream.publish(SnapshotWritten(replicatedLog.length, compactedLog.length))

    replicatedLog = compactedLog
  }

  private def simpleNames(refs: Iterable[ActorRef]) = refs.map(_.path.elements.last).mkString("{", ", ", "}")

}
