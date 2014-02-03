package pl.project13.scala.akka.raft.cluster

import pl.project13.scala.akka.raft.protocol._
import akka.actor.ActorRef

// mostly internal messages, to ease cluster interaction
object ClusterProtocol {

  /**
   * INTERNAL API
   * Adds one member to the cluster; Only to be used within [[pl.project13.scala.akka.raft.cluster.ClusterRaftActor]].
   * <p/>
   * This should not be acted upon directly, but should trigger a [[pl.project13.scala.akka.raft.protocol.RaftProtocol.ChangeConfiguration]]!
   */
  private[cluster] case class RaftMemberAdded(member: ActorRef) extends Message[Internal]

  /**
   * INTERNAL API
   * Removes one member to the cluster; Only to be used within [[pl.project13.scala.akka.raft.cluster.ClusterRaftActor]].
   * <p/>
   * !This should not be acted upon directly, but should trigger a [[pl.project13.scala.akka.raft.protocol.RaftProtocol.ChangeConfiguration]]!
   */
  private[cluster] case class RaftMemberRemoved(member: ActorRef) extends Message[Internal]

  case object AskForState                 extends Message[Internal]
  case class IAmInState(state: RaftState) extends Message[Internal]
}
