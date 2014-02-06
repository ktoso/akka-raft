package pl.project13.scala.akka.raft.protocol

import akka.actor.ActorRef
import scala.collection.immutable
import pl.project13.scala.akka.raft.model.{RaftSnapshot, Entry, ReplicatedLog, Term}
import pl.project13.scala.akka.raft.ClusterConfiguration

private[protocol] trait RaftProtocol extends Serializable {
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

      entries.headOption match {
        case Some(head) => new AppendEntries[T](term, replicatedLog.termAt(head.prevIndex), head.prevIndex, entries, leaderCommitId)
        case _          => new AppendEntries[T](term, Term(1), 1, entries, leaderCommitId)
      }

    }
  }


  /**
   * Raft extension: Snapshots (§7 Log Compaction)
   * Used by the Leader to install a snapshot onto an "catching up from very behind" Follower.
   * It's the Followers responsibility to load the snapshot data and apply it to it's state machine, by using akka-persistence provided mechanisms.
   */
  case class InstallSnapshot(snapshotMeta: RaftSnapshot)


  /**
   * Used to initiate configuration transitions.
   *
   * There can take a while to propagate, and will only be applied when the config is passed on to all nodes,
   * and the Leader (current, or new) is contained in the new cluster configuration - this may cause a re-election,
   * if we're about to remove the current leader from the cluster.
   *
   * For a detailed description see § 6 - Cluster Membership Changes, in the Raft whitepaper.
   */
  case class ChangeConfiguration(newConf: ClusterConfiguration) extends Message[Raft]

  /**
   * Message issued by freshly restarted actor after has crashed
   * Leader reacts with sending [[pl.project13.scala.akka.raft.protocol.RaftProtocol.ChangeConfiguration]]
   * */
  case object RequestConfiguration extends Message[Internal]
}
