package pl.project13.scala.akka.raft

import model._
import protocol._
import config.RaftConfig


private[raft] trait Candidate {
  this: RaftActor =>

  protected def raftConfig: RaftConfig

  val candidateBehavior: StateFunction = {
    // message from client, tell it that we know of no leader
    case Event(msg: ClientMessage[Command], m: ElectionMeta) =>
      log.info("Candidate got {} from client; Respond with anarchy - there is no leader.", msg)
      sender() ! LeaderIs(None, Some(msg))
      stay()

    // election
    case Event(BeginElection, m: ElectionMeta) =>
      if (m.config.members.isEmpty) {
        log.warning("Tried to initialize election with no members...")
        goto(Follower) using m.forFollower
      } else {
        log.info("Initializing election (among {} nodes) for {}", m.config.members.size, m.currentTerm)

        val request = RequestVote(m.currentTerm, m.clusterSelf, replicatedLog.lastTerm, replicatedLog.lastIndex)
        m.membersExceptSelf foreach { _ ! request }

        val includingThisVote = m.incVote
        stay() using includingThisVote.withVoteFor(m.currentTerm, m.clusterSelf)
      }

    case Event(msg: RequestVote, m: ElectionMeta) if m.canVoteIn(msg.term) =>
      sender ! VoteCandidate(m.currentTerm)
      stay() using m.withVoteFor(msg.term, candidate())

    case Event(msg: RequestVote, m: ElectionMeta) =>
      sender ! DeclineCandidate(msg.term)
      stay()

    case Event(VoteCandidate(term), m: ElectionMeta) =>
      val includingThisVote = m.incVote

      if (includingThisVote.hasMajority) {
        log.info("Received vote by {}; Won election with {} of {} votes", voter(), includingThisVote.votesReceived, m.config.members.size)
        goto(Leader) using m.forLeader
      } else {
        log.info("Received vote by {}; Have {} of {} votes", voter(), includingThisVote.votesReceived, m.config.members.size)
        stay() using includingThisVote
      }

    case Event(DeclineCandidate(term), m: ElectionMeta) =>
      log.info("Rejected vote by {}, in term {}", voter(), term)
      stay()

    // end of election

    // handle appends
    case Event(append: AppendEntries[Entry[Command]], m: ElectionMeta) =>
      val leaderIsAhead = append.term >= m.currentTerm

      if (leaderIsAhead) {
        log.info("Reverting to Follower, because got AppendEntries from Leader in {}, but am in {}", append.term, m.currentTerm)
        m.clusterSelf forward append
        goto(Follower) using m.forFollower
      } else {
        stay()
      }

    // ending election due to timeout
    case Event(ElectionTimeout, m: ElectionMeta) if m.config.members.size > 1 =>
      log.info("Voting timeout, starting a new election (among {})...", m.config.members.size)
      m.clusterSelf ! BeginElection
      stay() using m.forNewElection

    // would like to start election, but I'm all alone! ;-(
    case Event(ElectionTimeout, m: ElectionMeta) =>
      log.info("Voting timeout, unable to start election, don't know enough nodes (members: {})...", m.config.members.size)
      goto(Follower) using m.forFollower

    case Event(AskForState, _) =>
      sender() ! IAmInState(Candidate)
      stay()
  }

}
