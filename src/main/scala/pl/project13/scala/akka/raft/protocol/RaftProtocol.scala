package pl.project13.scala.akka.raft.protocol

import akka.actor.ActorRef
import pl.project13.scala.akka.raft.{Entry, Term}
import scala.collection.immutable

trait RaftProtocol {
  sealed trait RaftMessage extends Message[Raft]

  /**
   * Wrap messages you want to send to the underlying replicated state machine
   * TODO remove client param
   */
  case class ClientMessage[T <: AnyRef](@deprecated client: ActorRef, cmd: T) extends RaftMessage

  case class RequestVote(
    term: Term,
    candidateId: ActorRef,
    lastLogTerm: Term,
    lastLogIndex: Int
  ) extends RaftMessage

  case class AppendEntries[T <: AnyRef](
    currentTerm: Term,
    leader: ActorRef,
    prevLogIndex: Int,
    prevLogTerm: Term,
    entries: immutable.Seq[T]
  ) extends RaftMessage

}
