package pl.project13.scala.akka.raft

import model._
import protocol._
import cluster.ClusterProtocol.{IAmInState, AskForState}


private[raft] trait Candidate {
  this: RaftActor =>

  protected def raftConfig: RaftConfiguration

  val candidateBehavior: StateFunction = {
    // election
    case Event(BeginElection, m: ElectionMeta) =>
      if (m.config.members.isEmpty) {
        log.info("Tried to initialize election with no members...")
        goto(Follower) using m.forFollower
      } else {
        log.info(s"Initializing election (among ${m.config.members.size} nodes) for ${m.currentTerm}")

        val request = RequestVote(m.currentTerm, self, replicatedLog.lastTerm, replicatedLog.lastIndex)
        m.membersExceptSelf foreach { _ ! request }

        val includingThisVote = m.incVote
        stay() using includingThisVote.withVoteFor(m.currentTerm, self)
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
        log.info(s"Received vote by ${voter()}; Won election with ${includingThisVote.votesReceived} of ${m.config.members.size} votes")
        goto(Leader) using m.forLeader
      } else {
        log.info(s"Received vote by ${voter()}; Have ${includingThisVote.votesReceived} of ${m.config.members.size} votes")
        stay() using includingThisVote
      }

    case Event(DeclineCandidate(term), m: ElectionMeta) =>
      log.info(s"Rejected vote by ${voter()}, in $term")
      stay()

    // end of election

    // handle appends
    case Event(append: AppendEntries[Entry[Command]], m: ElectionMeta) =>
      val leaderIsAhead = append.term >= m.currentTerm

      if (leaderIsAhead) {
        log.info("Reverting to Follower, because got AppendEntries from Leader in {}, but am in {}", append.term, m.currentTerm)
        self.tell(append, sender())
        goto(Follower) using m.forFollower
      } else {
        stay()
      }

    // ending election due to timeout
    case Event(ElectionTimeout(since), m: ElectionMeta) if m.config.members.size > 1 =>
      log.info(s"Voting timeout, starting a new election (among ${m.config.members.size})...")
      self ! BeginElection
      stay() using m.forNewElection

    // would like to start election, but I'm all alone! ;-(
    case Event(ElectionTimeout(since), m: ElectionMeta) =>
      log.info(s"Voting timeout, unable to start election, don't know enough nodes (members: ${m.config.members.size})...")
      goto(Follower) using m.forFollower

    case Event(AskForState, _) =>
      sender() ! IAmInState(Candidate)
      stay()
  }

}
