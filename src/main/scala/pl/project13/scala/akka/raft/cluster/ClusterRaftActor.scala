package pl.project13.scala.akka.raft.cluster

import protocol._
import akka.cluster.{Member, Cluster}
import akka.cluster.ClusterEvent._
import concurrent.duration._
import pl.project13.scala.akka.raft.config.{RaftConfig, RaftConfiguration}
import pl.project13.scala.akka.raft.protocol._
import akka.actor._

/**
 * Akka cluster ready [[pl.project13.scala.akka.raft.RaftActor]].
 *
 * '''Requires cluster node role: "raft"'''
 *
 * In order to guarantee that raft is running on exactly the nodes in the cluster you want it to,
 * a Node on which a ClusterRaftActor can start MUST have the role `"raft"`, otherwise it will fail to initialize.
 * The role validation can be turned off, in case you want to start raft on all available nodes (without looking at the
 * presence of the "raft" role), but it is discouraged to do so.
 *
 * @param keepInitUntilFound keeps underlying `raftActor` in `Init` state, until this number of other raft actors has been auto-discovered
 */
// todo change the impl to make this REALLY transparent
class ClusterRaftActor(raftActor: ActorRef, keepInitUntilFound: Int) extends Actor with ActorLogging
  with ClusterRaftGrouping {

  val cluster = Cluster(context.system)

  val raftConfig = RaftConfiguration(context.system)

  checkIfRunningOnRaftNodeOrStop(raftConfig, cluster)

  import context.dispatcher

  val identifyTimeout = raftConfig.clusterAutoDiscoveryIdentifyTimeout

  /**
   * Used to keep track if we still need to retry sending Identify to an address.
   * If node is not here, it has responsed with at least one ActorIdentity.
   */
  private var awaitingIdentifyFrom = Set.empty[Address]

  override def preStart(): Unit = {
    super.preStart()
    log.info("Joining new raft actor to cluster, will watch {}", raftActor.path)

    // ClusterRaftActor will react with termination, if the raftActor terminates
    context watch raftActor

    // tell the raft actor, that this one is it's "outside world representative"
    raftActor ! AssignClusterSelf(self)

    cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])
  }

  override def postStop(): Unit = {
    context unwatch raftActor
    cluster unsubscribe self
    super.postStop()
  }

  def receive = {

    // members joining
    case MemberUp(member) if isRaftNode(member) =>
        log.info("Node is Up: {}, selecting and adding actors to Raft cluster..", member.address)
        tryIdentifyRaftMembers(member.address, RaftMembersIdentifyTimedOut(member.address, raftConfig.clusterAutoDiscoveryRetryCount))

    case MemberUp(member) =>
      log.debug("Another node joined, but it's does not have a [{}] role, ignoring it.", raftGroupRole)


    // identifying new members ------------------

    case ActorIdentity(address: Address, Some(raftActorRef)) =>
      log.info("Adding actor {} to Raft cluster, from address: {}", raftActorRef, address)
      // we got at-least-one response, if we get more that's good, but no need to retry
      awaitingIdentifyFrom -= address

      raftActor ! RaftMemberAdded(raftActorRef, keepInitUntilFound)

    case ActorIdentity(address: Address, None) =>
      log.warning("Unable to find any raft-actors on node: {}", address)
      awaitingIdentifyFrom -= address // == got at-least-one response

    case timedOut: RaftMembersIdentifyTimedOut
      if timedOut.shouldRetry  && awaitingIdentifyFrom.contains(timedOut.address) =>

      log.warning("Did not hear back for Identify call to {}, will try again {} more times...", timedOut.address, timedOut.retryMoreTimes)
      tryIdentifyRaftMembers(timedOut.address, timedOut.forRetry) // todo enable handling of these messages in any state, extend clustermanagementBehavior!

    case timedOut: RaftMembersIdentifyTimedOut =>
      log.debug("Did hear back from {}, stopping retry", timedOut.address)

    // end of identifying new members -----------


    // members going away
    case UnreachableMember(member) =>
      log.info("Node detected as unreachable: {}", member)
      // todo remove from raft ???

    case MemberRemoved(member, previousStatus) if member == self =>
      log.info("This member was removed from cluster, stopping self (prev status: {})", previousStatus)
      context stop self

    case MemberRemoved(member, previousStatus) =>
      log.info("Member is Removed: {} after {}", member.address, previousStatus)
      // todo remove from raft ???

    case _: MemberEvent =>
      // ignore

    case Terminated(watchedActor) if watchedActor == raftActor /* sanity check, really needed? */ =>
      context stop self

    case msg =>
      // all other messages, we proxy through to the RaftActor, it will handle the rest
      raftActor.tell(msg, sender())
  }


  private def tryIdentifyRaftMembers(address: Address, onIdentityTimeout: RaftMembersIdentifyTimedOut) {
    val memberSelection = context.actorSelection(raftMembersPath(address))

    // we need a response from this node
    awaitingIdentifyFrom += address
    
    context.system.scheduler.scheduleOnce(identifyTimeout, self, onIdentityTimeout)
    memberSelection ! Identify(address)
  }

  private def isRaftNode(member: Member) =
    member.roles contains raftGroupRole

  /**
   * If `check-for-raft-cluster-node-role` is enabled, will check if running on a node with the `"raft"` role.
   * If not running on a `"raft"` node, will throw an
   */
  protected def checkIfRunningOnRaftNodeOrStop(config: RaftConfig, cluster: Cluster) {
    if (!cluster.selfRoles.contains(raftGroupRole)) {
      log.warning(
        s"""Tried to initialize ${getClass.getSimpleName} on cluster node (${cluster.selfAddress}), but it's roles: """ +
        s"""${cluster.selfRoles} did not include the required ${raftGroupRole}role, so stopping this actor. """ +
         """Please verify your actor spawning logic, or update your configuration with akka.cluster.roles = [ "raft" ] for this node."""
      )

      context.system.stop(self)
    }
  }
}

object ClusterRaftActor {

  def props(raftActor: ActorRef, keepInitUntilMembers: Int) = {
    Props(classOf[ClusterRaftActor], raftActor, keepInitUntilMembers)
  }
}