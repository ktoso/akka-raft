package pl.project13.scala.akka.raft.protocol

import akka.actor.ActorRef
import pl.project13.scala.akka.raft.{Entry, Term}
import scala.collection.immutable

trait RaftProtocol {
  sealed trait RaftMessage extends Message[Raft]

  case class RequestVote(
    term: Term,
    candidateId: ActorRef,
    lastLogTerm: Term,
    lastLogIndex: Int
  ) extends RaftMessage

  type Entries[T <: AnyRef] = immutable.Seq[Entry[T]]

  case class AppendEntries[T <: AnyRef](
    currentTerm: Term,
    leader: ActorRef,
    prevLogIndex: Int,
    prevLogTerm: Term,
    entries: Entries[T]
  ) extends RaftMessage

}
