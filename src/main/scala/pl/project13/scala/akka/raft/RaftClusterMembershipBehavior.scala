package pl.project13.scala.akka.raft

import protocol._
import akka.actor.ActorRef

trait RaftClusterMembershipBehavior {
  this: RaftActor =>
  
  /** Waits for initial cluster configuration. Step needed before we can start voting for a Leader. */
  lazy val awaitInitialConfigurationBehavior: StateFunction = {
    case Event(ChangeConfiguration(initialConfig), m: Meta) =>
      log.info("Applying initial raft cluster configuration. Consists of [{}] nodes: {}",
        initialConfig.members.size,
        initialConfig.members.map(_.path.elements.last).mkString("{", ", ", "}"))

      val deadline = resetElectionDeadline()
      log.info("Finished init of new Raft member, becoming Follower. Initial election deadline: " + deadline)
      goto(Follower) using m.copy(config = initialConfig)

    case Event(msg: AppendEntries[Command], m: Meta) =>
      log.info("Got AppendEntries from a Leader, but am in Init state. Will ask for it's configuration and join Raft cluster.")
      leader() ! RequestConfiguration
      stay()
  }

  /** Handles adding / removing raft members; Should be handled in every state */
  lazy val clusterManagementBehavior: StateFunction = {
    // enter joint consensus phase of configuration comitting
    case Event(ChangeConfiguration(newConfiguration), m: Metadata) =>
      val transitioningConfig = m.config transitionTo newConfiguration

      log.info("Starting transition to new Configuration, old [size: {}]: {}, migrating to [size: {}]: {}",
        m.config.members.size, simpleNames(m.config.members),
        transitioningConfig.transitionToStable.members.size, transitioningConfig)

      // configuration change goes over the same process as log appending
      // here we initiate the 1st phase - committing the "joint consensus config", which includes all nodes from these configs
      // the 2nd phase is initiated upon committing of this entry to the log.
      // Once the new config is committed, nodes that are not included can step-down
      self ! ClientMessage(self, transitioningConfig)

      stay()
  }

  private def simpleNames(refs: Iterable[ActorRef]) = refs.map(_.path.elements.last).mkString("{", ", ", "}")

}
