package pl.project13.scala.akka.raft.protocol

import akka.actor.ActorRef

private[protocol] trait RaftClusterMembershipProtocol {

  /**
   * Tells an [[pl.project13.scala.akka.raft.RaftActor]] that it should consider the given actorRef as it's "external", ActorRef.
   * This is used together with [[pl.project13.scala.akka.raft.cluster.ClusterRaftActor]], which acts as an proxy between the cluster and the RaftActor.
   *
   * The only real application of this is `def others = members filterNot { _ == clusterSelf }`, when determining where to send messages from a Leader.
   */
  private[raft] case class AssignClusterSelf(clusterSelf: ActorRef) extends Message[InternalCluster] // todo smart or hack? Unsure yet...


  // todo expand to cover also after-init cases or drop this?

  /**
   * Removes one member to the cluster; Used in discovery phase, during Init state of RaftActor in clustered setup.
   */
  private[raft] case class RaftMemberAdded(member: ActorRef, keepInitUntil: Int) extends Message[Internal]

  /**
   * Removes one member to the cluster; Used in discovery phase, during Init state of RaftActor in clustered setup.
   */
  private[raft] case class RaftMemberRemoved(member: ActorRef, keepInitUntil: Int) extends Message[Internal]

}

