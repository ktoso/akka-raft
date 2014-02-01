package pl.project13.scala.akka.raft.cluster

import pl.project13.scala.akka.raft.protocol.{Internal, Message}
import akka.actor.ActorRef
import pl.project13.scala.akka.raft.protocol.RaftStates.RaftState

// mostly internal messages, to ease cluster interaction
object ClusterProtocol {

  case object AskForState                 extends Message[Internal]
  case class IAmInState(state: RaftState) extends Message[Internal]
}
