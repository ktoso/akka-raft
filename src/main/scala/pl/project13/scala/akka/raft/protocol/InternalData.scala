package pl.project13.scala.akka.raft.protocol

import akka.actor.ActorRef
import pl.project13.scala.akka.raft.Term

trait InternalData {

  sealed trait Metadata {
    def self: ActorRef
    def votedFor: Option[ActorRef]
    def currentTerm: Term
    def members: Vector[ActorRef]
    lazy val others = members filterNot { _ == self }


    /** Since I'm the Leader "everyone but myself" */
    def membersExceptSelf(implicit self: ActorRef) = members filterNot { _ == self}

    /** A member can only vote once during one Term */
    def canVote = votedFor.isEmpty
    /** A member can only vote once during one Term */
    def cannotVote = votedFor.isDefined

  }

  case class Meta(
    self: ActorRef,
    currentTerm: Term,
    members: Vector[ActorRef],
    votedFor: Option[ActorRef] = None
  ) extends Metadata {
    
    // transition helpers
    def forNewElection: ElectionMeta = ElectionMeta(self, currentTerm.next, 0, members, None)

    def withVoteFor(candidate: ActorRef) = {
      require(canVote, "Tried to vote twice!")
      copy(votedFor = Some(candidate))
    }
  }

  case class ElectionMeta(
    self: ActorRef,
    currentTerm: Term,
    votesReceived: Int,
    members: Vector[ActorRef],
    votedFor: Option[ActorRef] = None
  ) extends Metadata {

    def hasMajority = votesReceived > members.size / 2

    // transistion helpers
    def incVote                          = copy(votesReceived = votesReceived + 1)
    def incTerm                          = copy(currentTerm = currentTerm.next)
    def withVoteFor(candidate: ActorRef) = copy(votedFor = Some(candidate))

    def forLeader: LeaderMeta = LeaderMeta(self, currentTerm, members)
    def forFollower: Meta     = Meta(self, currentTerm, members, None)
  }

  case class LeaderMeta(
    self: ActorRef,
    currentTerm: Term,
    members: Vector[ActorRef]
  ) extends Metadata {

    val votedFor = None

    def forFollower: Meta = Meta(self, currentTerm, members)
  }

  object Meta {
    def initial(implicit self: ActorRef) = new Meta(self, Term(1), Vector.empty, None)
  }
}
