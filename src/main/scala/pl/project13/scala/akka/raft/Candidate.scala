package pl.project13.scala.akka.raft

import model._
import protocol._
import config.RaftConfig


private[raft] trait Candidate {
  this: RaftActor =>

  protected def raftConfig: RaftConfig

  val candidateBehavior: StateFunction = {
    // message from client, tell it that we know of no leader
    case Event(msg: ClientMessage[Command], m: Meta) =>
      log.info("Candidate got {} from client; Respond with anarchy - there is no leader.", msg)
      sender() ! LeaderIs(None, Some(msg))
      stay()

    // election
    case Event(msg @ BeginElection, m: Meta) =>
      if(raftConfig.publishTestingEvents) context.system.eventStream.publish(ElectionStarted(m.currentTerm, self))

      if (m.config.members.isEmpty) {
        log.warning("Tried to initialize election with no members...")
        goto(Follower) applying GoToFollowerEvent()
      } else {
        log.info("Initializing election (among {} nodes) for {}", m.config.members.size, m.currentTerm)

        val request = RequestVote(m.currentTerm, m.clusterSelf, replicatedLog.lastTerm, replicatedLog.lastIndex)
        m.membersExceptSelf foreach { _ ! request }

        stay() applying VoteForSelfEvent()
      }

    case Event(msg: RequestVote, m: Meta) if msg.term < m.currentTerm =>
      log.info("Rejecting RequestVote msg by {} in {}. Received stale {}.", candidate, m.currentTerm, msg.term)
      candidate ! DeclineCandidate(m.currentTerm)
      stay()

    case Event(msg: RequestVote, m: Meta) if msg.term > m.currentTerm =>
      log.info("Received newer {}. Current term is {}. Revert to follower state.", msg.term, m.currentTerm)
      goto(Follower) applying GoToFollowerEvent(Some(msg.term))

    case Event(msg: RequestVote, m: Meta) =>
      if (m.canVoteIn(msg.term)) {
        log.info("Voting for {} in {}.", candidate, m.currentTerm)
        candidate ! VoteCandidate(m.currentTerm)
        stay() applying VoteForEvent(candidate)
      } else {
        log.info("Rejecting RequestVote msg by {} in {}. Already voted for {}", candidate, m.currentTerm, m.votedFor.get)
        sender ! DeclineCandidate(m.currentTerm)
        stay()
      }

    case Event(VoteCandidate(term), m: Meta) if term < m.currentTerm =>
      log.info("Rejecting VoteCandidate msg by {} in {}. Received stale {}.", voter(), m.currentTerm, term)
      voter ! DeclineCandidate(m.currentTerm)
      stay()

    case Event(VoteCandidate(term), m: Meta) if term > m.currentTerm =>
      log.info("Received newer {}. Current term is {}. Revert to follower state.", term, m.currentTerm)
      goto(Follower) applying GoToFollowerEvent(Some(term))

    case Event(VoteCandidate(term), m: Meta) =>
      val votesReceived = m.votesReceived + 1

      val hasWonElection = votesReceived > m.config.members.size / 2
      if (hasWonElection) {
        log.info("Received vote by {}. Won election with {} of {} votes", voter(), votesReceived, m.config.members.size)
        goto(Leader) applying GoToLeaderEvent()
      } else {
        log.info("Received vote by {}. Have {} of {} votes", voter(), votesReceived, m.config.members.size)
        stay applying IncrementVoteEvent()
      }

    case Event(DeclineCandidate(term), m: Meta) =>
      if (term > m.currentTerm) {
        log.info("Received newer {}. Current term is {}. Revert to follower state.", term, m.currentTerm)
        goto(Follower) applying GoToFollowerEvent(Some(term))
      } else {
        log.info("Candidate is declined by {} in term {}", sender(), m.currentTerm)
        stay()
      }


    // end of election

    // handle appends
    case Event(append: AppendEntries[Entry[Command]], m: Meta) =>
      val leaderIsAhead = append.term >= m.currentTerm

      if (leaderIsAhead) {
        log.info("Reverting to Follower, because got AppendEntries from Leader in {}, but am in {}", append.term, m.currentTerm)
        m.clusterSelf forward append
        goto(Follower) applying GoToFollowerEvent()
      } else {
        stay()
      }

    // ending election due to timeout
    case Event(ElectionTimeout, m: Meta) if m.config.members.size > 1 =>
      log.info("Voting timeout, starting a new election (among {})...", m.config.members.size)
      m.clusterSelf ! BeginElection
      stay() applying StartElectionEvent()

    // would like to start election, but I'm all alone! ;-(
    case Event(ElectionTimeout, m: Meta) =>
      log.info("Voting timeout, unable to start election, don't know enough nodes (members: {})...", m.config.members.size)
      goto(Follower) applying GoToFollowerEvent()

    case Event(AskForState, _) =>
      sender() ! IAmInState(Candidate)
      stay()
  }

}
