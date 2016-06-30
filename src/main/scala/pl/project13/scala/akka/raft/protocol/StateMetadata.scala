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
    def votedFor: Option[Candidate]
    def currentTerm: Term

    def config: ClusterConfiguration
    def isConfigTransitionInProgress = config.isTransitioning

    /** Since I'm the Leader "everyone but myself" */
    def membersExceptSelf = config.members filterNot { _ == clusterSelf }

    def members = config.members

    /** A member can only vote once during one Term */
    def canVoteIn(term: Term) = votedFor.isEmpty && term == currentTerm

    /** A member can only vote once during one Term */
    def cannotVoteIn(term: Term) = term < currentTerm || votedFor.isDefined

  }

  case class Meta(
    clusterSelf: ActorRef,
    currentTerm: Term,
    config: ClusterConfiguration,
    votedFor: Option[Candidate] = None,
    votesReceived: Int = 0
  ) extends Metadata {

    // transition helpers
    def forNewElection: Meta = Meta(clusterSelf, currentTerm.next, config)

    def withTerm(term: Term) = copy(currentTerm = term, votedFor = None)
    def incTerm = copy(currentTerm = currentTerm.next)

    def withVoteFor(candidate: ActorRef) = copy(votedFor = Some(candidate))


    def withConfig(conf: ClusterConfiguration): Meta = copy(config = conf)

    def hasMajority = votesReceived > config.members.size / 2

    def incVote = copy(votesReceived = votesReceived + 1)

    def forLeader: Meta = Meta(clusterSelf, currentTerm, config)
    def forFollower(term: Term = currentTerm): Meta = Meta(clusterSelf, term, config)
  }

  object Meta {
    def initial(implicit self: ActorRef) = new Meta(self, Term(0), ClusterConfiguration(), None, 0)
  }
}
