package pl.project13.scala.akka.raft.protocol

import akka.actor.ActorRef
import pl.project13.scala.akka.raft.model.Term
import pl.project13.scala.akka.raft.ClusterConfiguration

// todo simplify maybe, 1 metadata class would be enough I guess
@SerialVersionUID(1L)
private[protocol] trait StateMetadata extends Serializable {

  type Candidate = ActorRef

  sealed trait Metadata {
    /**
     * A raft cluster proxy aware version of `self` - should always be used it `self` is about to be exposed to external actors.
     *
     * In practice, this will be `self` if __not__ running in clustered mode, and the [[pl.project13.scala.akka.raft.cluster.ClusterRaftActor]]
     * `ActorRef` that proxies this actor when in a clustered enviroment.
     */
    implicit def clusterSelf: ActorRef // todo rethink if needed, better yet rewrite ClusterRaftActor to be properly transparent for RaftActor if possible.
    def votes: Map[Term, Candidate]
    def currentTerm: Term

    def config: ClusterConfiguration
    def isConfigTransitionInProgress = config.isTransitioning

    /** Since I'm the Leader "everyone but myself" */
    def membersExceptSelf = config.members filterNot { _ == clusterSelf }

    def members = config.members

    /** A member can only vote once during one Term */
    def canVoteIn(term: Term) = term >= currentTerm && votes.get(term).isEmpty

    /** A member can only vote once during one Term */
    def cannotVoteIn(term: Term) = term < currentTerm || votes.get(term).isDefined

  }

  case class Meta(
    clusterSelf: ActorRef,
    currentTerm: Term,
    config: ClusterConfiguration,
    votes: Map[Term, Candidate]
  ) extends Metadata {
    
    // transition helpers
    def forNewElection: ElectionMeta = ElectionMeta(clusterSelf, currentTerm.next, 0, config, votes)

    def withVote(term: Term, candidate: ActorRef) = {
      copy(votes = votes updated (term, candidate))
    }

    def withConfig(conf: ClusterConfiguration): Meta = copy(config = conf)
  }

  object Meta {
    def initial(implicit self: ActorRef) = new Meta(self, Term(0), ClusterConfiguration(), Map.empty)
  }

  case class ElectionMeta(
    clusterSelf: ActorRef,
    currentTerm: Term,
    votesReceived: Int,
    config: ClusterConfiguration,
    votes: Map[Term, Candidate]
  ) extends Metadata {

    def hasMajority = votesReceived > config.members.size / 2

    // transistion helpers
    def incVote = copy(votesReceived = votesReceived + 1)
    def incTerm = copy(currentTerm = currentTerm.next)

    def withVoteFor(term: Term, candidate: ActorRef) = copy(votes = votes + (term -> candidate))

    def forLeader: LeaderMeta        = LeaderMeta(clusterSelf, currentTerm, config)
    def forFollower: Meta            = Meta(clusterSelf, currentTerm, config, Map.empty)
    def forNewElection: ElectionMeta = this.forFollower.forNewElection
  }

  case class LeaderMeta(
    clusterSelf: ActorRef,
    currentTerm: Term,
    config: ClusterConfiguration
  ) extends Metadata {

    val votes = Map.empty[Term, Candidate]

    // todo duplication; yeah, having 3 meta classes was a bad idea. todo make one Meta class
    def withConfig(conf: ClusterConfiguration): LeaderMeta = copy(config = conf)

    def forFollower: Meta = Meta(clusterSelf, currentTerm, config, Map.empty)
  }

}
