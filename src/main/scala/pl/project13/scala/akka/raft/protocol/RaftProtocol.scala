package pl.project13.scala.akka.raft.protocol

import akka.actor.ActorRef
import scala.collection.immutable
import pl.project13.scala.akka.raft.model.{Entry, ReplicatedLog, Term}

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
    entries: immutable.Seq[Entry[T]],
    leaderCommitId: Int
  ) extends RaftMessage {

    def isHeartbeat = entries.isEmpty
    def isNotHeartbeat = !isHeartbeat

    override def toString = s"""AppendEntries(term:$term,prevLog:($prevLogTerm,$prevLogIndex),entries:$entries)"""
  }

  object AppendEntries {
    def apply[T](term: Term, replicatedLog: ReplicatedLog[T], fromIndex: Int, leaderCommitId: Int): AppendEntries[T] = {
      val entries = replicatedLog.entriesBatchFrom(fromIndex)
//      println("fromIndex = " + fromIndex + ";entries: " + replicatedLog.entries + "; batch: " + entries)

      entries.headOption match {
        case Some(head) => new AppendEntries[T](term, replicatedLog.termAt(head.prevIndex), head.prevIndex, entries, leaderCommitId)
        case _          => new AppendEntries[T](term, Term(1), 1, entries, leaderCommitId)
      }

    }
  }
}
