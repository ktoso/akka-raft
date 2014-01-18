package pl.project13.scala.akka.raft.protocol

import akka.actor.ActorRef
import scala.collection.immutable
import pl.project13.scala.akka.raft.{Entry, Term}

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
    log: Entries[T],
    votedFor: Option[ActorRef] = None
  ) extends RaftMessage

}
