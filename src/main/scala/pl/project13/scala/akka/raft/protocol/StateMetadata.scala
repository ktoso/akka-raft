package pl.project13.scala.akka.raft.protocol

import akka.actor.ActorRef
import pl.project13.scala.akka.raft.Term

trait StateMetadata {

  type Candidate = ActorRef

  sealed trait Metadata {
    def self: ActorRef
    def votes: Map[Term, Candidate]
    def currentTerm: Term

    def commitIndex: Int
    def lastAppliedIndex: Int

    def members: Vector[ActorRef]
    val others = members filterNot { _ == self }


    /** Since I'm the Leader "everyone but myself" */
    def membersExceptSelf(implicit self: ActorRef) = members filterNot { _ == self}

    /** A member can only vote once during one Term */
    def canVoteIn(term: Term) = term >= currentTerm && votes.get(term).isEmpty

    /** A member can only vote once during one Term */
    def cannotVoteIn(term: Term) = term < currentTerm || votes.get(term).isDefined

  }

  case class Meta(
    self: ActorRef,
    currentTerm: Term,
    commitIndex: Int,
    lastAppliedIndex: Int,
    members: Vector[ActorRef],
    votes: Map[Term, Candidate]
  ) extends Metadata {
    
    // transition helpers
    def forNewElection: ElectionMeta = ElectionMeta(self, currentTerm.next, commitIndex, lastAppliedIndex, 0, members, votes)

    def withVote(term: Term, candidate: ActorRef) = {
      copy(votes = votes updated (term, candidate))
    }
  }

  case class ElectionMeta(
    self: ActorRef,
    currentTerm: Term,
    commitIndex: Int,
    lastAppliedIndex: Int,
    votesReceived: Int,
    members: Vector[ActorRef],
    votes: Map[Term, Candidate]
  ) extends Metadata {

    def hasMajority = votesReceived > members.size / 2

    // transistion helpers
    def incVote = copy(votesReceived = votesReceived + 1)
    def incTerm = copy(currentTerm = currentTerm.next)

    def withVoteFor(term: Term, candidate: ActorRef) = copy(votes = votes + (term -> candidate))

    def forLeader: LeaderMeta        = LeaderMeta(self, currentTerm, commitIndex, lastAppliedIndex, members)
    def forFollower: Meta            = Meta(self, currentTerm, commitIndex, lastAppliedIndex, members, Map.empty)
    def forNewElection: ElectionMeta = this.forFollower.forNewElection
  }

  case class LeaderMeta(
    self: ActorRef,
    currentTerm: Term,
    commitIndex: Int,
    lastAppliedIndex: Int,
    members: Vector[ActorRef]
  ) extends Metadata {

    val votes = Map.empty[Term, Candidate]

    def forFollower: Meta = Meta(self, currentTerm, commitIndex, lastAppliedIndex, members, Map.empty)
  }

  object Meta {
    def initial(implicit self: ActorRef) = new Meta(self, Term(0), -1, -1, Vector.empty, Map.empty)
  }
}
