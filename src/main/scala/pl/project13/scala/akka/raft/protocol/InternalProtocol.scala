package pl.project13.scala.akka.raft.protocol

import akka.actor.ActorRef
import pl.project13.scala.akka.raft.model.Term
import pl.project13.scala.akka.raft.protocol.RaftStates.RaftState

trait InternalProtocol {

  // todo maybe throw out
  // just some types to make it more clear when these messages are sent, not actualy used (could be stripped)
  sealed trait InternalMessage  extends Message[Internal]
  sealed trait FollowerResponse extends Message[Internal]
  sealed trait ElectionMessage  extends Message[Internal]
  sealed trait LeaderMessage    extends Message[Internal]

  case object BeginElection     extends ElectionMessage
  case class Vote(term: Term)   extends ElectionMessage
  case class Reject(term: Term) extends ElectionMessage // todo needs better name

  case class ElectedAsLeader()   extends ElectionMessage
  case class ElectionTimeout(since: Long)   extends ElectionMessage

  /** When the Leader has sent an append, for an unexpected number, the Follower replies with this */
  sealed trait AppendResponse extends FollowerResponse {
    /** currentTerm for leader to update in the `nextTerm` lookup table */
    def term: Term
  }
  case class AppendRejected(term: Term, lastIndex: Int)   extends AppendResponse
  case class AppendSuccessful(term: Term, lastIndex: Int) extends AppendResponse

  case object SendHeartbeat extends LeaderMessage

  // internal cluster related messages
  sealed abstract class MembersChanged extends Message[Internal]
  case class MemberAdded(member: ActorRef) extends MembersChanged
  case class MemberRemoved(member: ActorRef) extends MembersChanged

  // todo handle this smarter?
  case object AskForState extends Message[Internal]
  case class IAmInState(s: RaftState) extends Message[Internal]
}

object InternalProtocol extends InternalProtocol