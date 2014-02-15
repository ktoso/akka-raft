package pl.project13.scala.akka.raft.cluster

import akka.actor.{RootActorPath, ActorPath, Address}

trait ClusterRaftGrouping {

  /**
   * ActorPath where to look on remote members for raft actors, when a new node joins the cluster.
   *
   * Defaults to: `RootActorPath(nodeAddress) / "user" / "raft-member-*"`
   */
  def raftMembersPath(nodeAddress: Address): ActorPath = RootActorPath(nodeAddress) / "user" / "raft-member-*"

  /**
   * Only nodes with this role will participate in this raft cluster.
   *
   * Detaults to `"raft"`, but you can override it in order to support multiple raft clusters in the same actor system
   */
  def raftGroupRole: String = "raft"

}
