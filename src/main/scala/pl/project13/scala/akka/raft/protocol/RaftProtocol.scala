package pl.project13.scala.akka.raft.protocol

import akka.actor.ActorRef
import pl.project13.scala.akka.raft.{ReplicatedLog, Entry, Term}
import scala.collection.immutable

trait RaftProtocol {
  sealed trait RaftMessage extends Message[Raft]

  /**
   * Wrap messages you want to send to the underlying replicated state machine
   * TODO remove client param
   */
  case class ClientMessage[T](@deprecated client: ActorRef, cmd: T) extends RaftMessage

  case class RequestVote(
    term: Term,
    candidateId: ActorRef,
    lastLogTerm: Term,
    lastLogIndex: Int
  ) extends RaftMessage

  case class AppendEntries[T](
    term: Term,
    prevLogTerm: Term,
    prevLogIndex: Int,
    entries: immutable.Seq[Entry[T]]
  ) extends RaftMessage {

    def isHeartbeat = entries.isEmpty
    def isNotHeartbeat = !isHeartbeat

    override def toString = s"""AppendEntries(term:$term,prevLog:($prevLogTerm,$prevLogIndex),entries:$entries)"""
  }

  object AppendEntries {
    def apply[T](term: Term, replicatedLog: ReplicatedLog[T], fromIndex: Int): AppendEntries[T] = {
      val entries = replicatedLog.entriesBatchFrom(fromIndex)

      entries.headOption match {
        case Some(head) => new AppendEntries[T](term, replicatedLog.termAt(head.prevIndex), head.prevIndex, entries)
        case _          => new AppendEntries[T](term, Term(1), 1, Nil)
      }

    }
  }
}
