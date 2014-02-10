package pl.project13.scala.akka.raft.cluster

import akka.actor.{ActorIdentity, Identify, RootActorPath, Actor}
import akka.cluster.{Member, Cluster}
import akka.cluster.ClusterEvent._
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.ClusterEvent.UnreachableMember
import pl.project13.scala.akka.raft.RaftActor
import pl.project13.scala.akka.raft.protocol._
import akka.util.Timeout
import concurrent.duration._
import pl.project13.scala.akka.raft.cluster.ClusterProtocol.{RaftMemberRemoved, RaftMemberAdded}
import pl.project13.scala.akka.raft.config.{RaftConfig, RaftConfiguration}

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
 *
 */
trait ClusterRaftActor extends RaftActor {

  val cluster = Cluster(context.system)

  checkIfRunningOnRaftNodeOrStop(raftConfig, cluster)

  import context.dispatcher
  
  implicit val timeout = Timeout(2.seconds) // todo make configurable via config

  abstract override def preStart(): Unit = {
    super.preStart()
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])
  }

  abstract override def postStop(): Unit = {
    cluster.unsubscribe(self)
    super.postStop()
  }

  override def aroundReceive(receive: Actor.Receive, msg: Any): Unit = msg match {

    // members joining
    case MemberUp(member) if isRaftNode(member) =>
        log.info("Node is Up: {}, selecting and adding actors to Raft cluster..", member.address)
        // todo make naming configurable
        val memberSelection = context.actorSelection(RootActorPath(member.address) / "user" / "member-*")
        memberSelection ! Identify(member.address)

    case MemberUp(member) =>
      log.debug("Another node joined, but it's does not have a [{}] role, ignoring it.", raftConfig.raftRoleName)

    case ActorIdentity(address, Some(raftActorRef)) =>
      log.info("Adding actor {} to Raft cluster, from address: {}", raftActorRef, address)
      self ! RaftMemberAdded(raftActorRef)

    case ActorIdentity(address, None) =>
      log.warning("Unable to find any raft-actors on node: {}", address)

    // members going away
    case UnreachableMember(member) =>
      log.info("Node detected as unreachable: {}", member)
      // todo remove from raft ???

    case MemberRemoved(member, previousStatus) =>
      log.info("Member is Removed: {} after {}", member.address, previousStatus)
      // todo remove from raft ???

    case _: MemberEvent =>
      // ignore

    case _ =>
      // everything else just push to the RaftActor's receive
      super.aroundReceive(receive, msg)
  }

  protected def isRaftNode(member: Member) =
    member.roles contains raftConfig.raftRoleName

  /**
   * If `check-for-raft-cluster-node-role` is enabled, will check if running on a node with the `"raft"` role.
   * If not running on a `"raft"` node, will throw an
   */
  protected def checkIfRunningOnRaftNodeOrStop(config: RaftConfig, cluster: Cluster) {
    if (!cluster.selfRoles.contains(config.raftRoleName)) {
      log.warning(
        s"""Tried to initialize ${getClass.getSimpleName} on cluster node (${cluster.selfAddress}), but it's roles: """ +
        s"""${cluster.selfRoles} did not include the required ${raftConfig.raftRoleName}role, so stopping this actor. """ +
         """Please verify your actor spawning logic, or update your configuration with akka.cluster.roles = [ "raft" ] for this node."""
      )

      context.system.stop(self)
    }
  }
}
