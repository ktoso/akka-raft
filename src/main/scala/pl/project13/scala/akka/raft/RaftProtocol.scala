package pl.project13.scala.akka.raft

import scala.collection.immutable
import akka.actor.ActorRef

package object states {
  sealed trait RaftState
  case object Follower  extends RaftState
  case object Candidate extends RaftState
  case object Leader    extends RaftState
}

package object messages {
  trait Message[T]
  final class Internal
  final class Raft

  // raft messages
  sealed trait RaftMessage extends Message[Raft]
  case class RequestVote(candidatesLogIndex: Int) extends RaftMessage
  object RequestVote {
    val Zero = RequestVote(0)
  }
  case class AppendEntries(term: Term, log: immutable.Seq[Any]) extends RaftMessage

  // internal messages
  sealed trait InternalMessage extends Message[Internal]
  sealed trait FollowerResponse extends Message[Internal]

  case class ElectedAsLeader() extends InternalMessage
  case class ElectedLeader() extends InternalMessage

  /** When the Leader has sent an append, for an unexpected number, the Follower replies with this */
  case object AppendRejected extends FollowerResponse
  case object AppendSuccessful extends FollowerResponse

  // other datatypes

  /** committed == stored on majority of servers */
  case class RaftLogEntry(termNr: Long, payload: AnyRef) {
    def index = ??? // take from array
  }

  case class FollowerRef(ref: ActorRef, private var _nextIndex: Int = 1) {
    def incrementNextIndex(): Unit = nextIndex += 1
    def decrementNextIndex(): Unit = {
      require(nextIndex > 0, s"nextIndex by definition must be positive, yet asked to decrement beyond 0, for actorRef: $ref")
      nextIndex -= 1
    }
    def setNextIndex(n: Int): Unit = _nextIndex = n
    def nextIndex: Int = _nextIndex
  }

  final case class Term(termNr: Long) extends AnyVal {
    def +(n: Long): Term = Term(termNr + n)
    def <(otherTerm: Term): Boolean = this.termNr < otherTerm.termNr
    def >=(otherTerm: Term): Boolean = this.termNr >= otherTerm.termNr
  }
  object Term {
    val Zero = Term(0)
  }
}
