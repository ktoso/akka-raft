package pl.project13.scala.akka.raft.protocol

import akka.actor.ActorRef

trait InternalData {

  sealed trait Metadata {
    def self: ActorRef
    def members: Vector[ActorRef]
    lazy val others = members filterNot { _ == self }

    def votedFor: Option[ActorRef]
    
    /** Since I'm the Leader "everyone but myself" */
    def membersExceptSelf(implicit self: ActorRef) = members filterNot { _ == self}

    /** A member can only vote once during one Term */
    def canVote = votedFor.isEmpty
    /** A member can only vote once during one Term */
    def cannotVote = votedFor.isDefined

  }

  case class Meta(self: ActorRef, members: Vector[ActorRef], votedFor: Option[ActorRef] = None) extends Metadata {
    
    // transition helpers
    def forNewElection: ElectionMeta = ElectionMeta(self, 0, members, None)

    def withVoteFor(candidate: ActorRef) = {
      require(canVote, "Tried to vote twice!")
      copy(votedFor = Some(candidate))
    }
  }

  case class ElectionMeta(self:ActorRef, votesReceived: Int, members: Vector[ActorRef], votedFor: Option[ActorRef] = None) extends Metadata {
    def hasMajority = votesReceived > members.size / 2
    def withOneMoreVote = copy(votesReceived = votesReceived + 1)

    // transistion helpers
    def withVoteFor(candidate: ActorRef) = copy(votedFor = Some(candidate))
    def forLeader: LeaderMeta = LeaderMeta(self, members)
    def forFollower: Meta = Meta(self, members)
  }

  case class LeaderMeta(self: ActorRef, members: Vector[ActorRef]) extends Metadata {
    val votedFor = None

    def forFollower: Meta = Meta(self, members)
  }

  object Meta {
    def initial(implicit self: ActorRef) = new Meta(self, Vector.empty, None)
  }
}
