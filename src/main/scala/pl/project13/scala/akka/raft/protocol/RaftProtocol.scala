package pl.project13.scala.akka.raft.protocol

import akka.actor.ActorRef
import scala.collection.immutable
import pl.project13.scala.akka.raft.model.{RaftSnapshot, Entry, ReplicatedLog, Term}
import pl.project13.scala.akka.raft.ClusterConfiguration

private[protocol] trait RaftProtocol extends Serializable {
  sealed trait RaftMessage extends Message[Raft]

  /**
   * Wrap messages you want to send to the underlying replicated state machine
   */
  case class ClientMessage[T](client: ActorRef, cmd: T) extends RaftMessage


  /**
   * Message sent by a [[pl.project13.scala.akka.raft.Candidate]] in order to win an election (and become [[pl.project13.scala.akka.raft.Leader]]).
   *
   * @see §5.2 Leader Election and §5.4.1 - Election Restriction
   */
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
   * Used by Members to notify [[pl.project13.scala.akka.raft.RaftClientActor]] actors that they're sending their requests to a non-leader, and
   * that they should update their internal `leader` ActorRef to the given one.
   *
   * Upon receive of such message, all further communication should be done with the given actorRef.
   *
   * @see §8 - Client Interaction
   *      
   * @param ref the leader's ActorRef if known by the contacted member (or None if unknown, or the member is just candidating to become the new Leader)
   * @param msg if this message is sent in response to a client sending `msg` to a Follower or Candidate,
   *            they could not take the write, and the client must retry sending this message once it knows the Leader's ActorRef.
   *            To ease this, the "rejected" message will be carried in this parameter.
   */
  case class LeaderIs(ref: Option[ActorRef], msg: Option[Any]) extends Message[Raft]
  case object WhoIsTheLeader                                   extends Message[Raft]


  /**
   * Raft extension: Snapshots
   * Used by the Leader to install a snapshot onto an "catching up from very behind" Follower.
   * It's the Followers responsibility to load the snapshot data and apply it to it's state machine, by using akka-persistence provided mechanisms.
   *
   * @see §7 - Log Compaction
   */
  case class InstallSnapshot(snapshot: RaftSnapshot) extends Message[Raft]


  /**
   * Raft extension: Cluster Membership Changes / Joint Consensus
   *
   * There can take a while to propagate, and will only be applied when the config is passed on to all nodes,
   * and the Leader (current, or new) is contained in the new cluster configuration - this may cause a re-election,
   * if we're about to remove the current leader from the cluster.
   *
   * @see §6 - Cluster Membership Changes
   */
  case class ChangeConfiguration(newConf: ClusterConfiguration) extends Message[Raft]

  /**
   * Message issued by freshly restarted actor after has crashed
   * Leader reacts with sending [[pl.project13.scala.akka.raft.protocol.RaftProtocol.ChangeConfiguration]]
   *
   * @see §6 - Cluster Membership Changes
   */
  case object RequestConfiguration extends Message[Internal]
}
