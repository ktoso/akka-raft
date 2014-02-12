package pl.project13.scala.akka.raft.protocol

import pl.project13.scala.akka.raft.model.Term
import akka.actor.ActorRef

private[protocol] trait InternalProtocol extends Serializable {

  // just some types to make it more clear when these messages are sent, not actualy used (could be stripped)
  sealed trait InternalMessage  extends Message[Internal]
  sealed trait FollowerResponse extends Message[Internal]
  sealed trait ElectionMessage  extends Message[Internal]
  sealed trait LeaderMessage    extends Message[Internal]

  case object BeginElection     extends ElectionMessage
  case class VoteCandidate(term: Term)    extends ElectionMessage
  case class DeclineCandidate(term: Term) extends ElectionMessage

  case object ElectedAsLeader   extends ElectionMessage
  case object ElectionTimeout    extends ElectionMessage

  /** When the Leader has sent an append, for an unexpected number, the Follower replies with this */
  sealed trait AppendResponse extends FollowerResponse {
    /** currentTerm for leader to update in the `nextTerm` lookup table */
    def term: Term
  }
  case class AppendRejected(term: Term, lastIndex: Int)   extends AppendResponse
  case class AppendSuccessful(term: Term, lastIndex: Int) extends AppendResponse

  /** Internal msg sent to actor which should start a snapshotting process */
  case object InitLogSnapshot extends Message[Internal]

  case object SendHeartbeat extends LeaderMessage

  private[raft] case object AskForState                 extends Message[Internal]
  private[raft] case class IAmInState(state: RaftState) extends Message[Internal]


  // ----    testing and monitoring messages     ----
  case class EntryCommitted(idx: Int, on: ActorRef) extends Message[Testing]
  case class SnapshotWritten(initialSize: Int, compactedSize: Int) extends Message[Testing]
  // ---- end of testing and monitoring messages ----
}
