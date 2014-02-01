package pl.project13.scala.akka.raft

import scala.collection.immutable

import protocol._
import pl.project13.scala.akka.raft.model.Entry
import pl.project13.scala.akka.raft.protocol.RaftStates._
import pl.project13.scala.akka.raft.cluster.ClusterProtocol.{IAmInState, AskForState}

private[raft] trait Follower {
  this: RaftActor =>

  val followerBehavior: StateFunction = {

    // election
    case Event(RequestVote(term, candidate, lastLogTerm, lastLogIndex), m: Meta)
      if m.canVoteIn(term) =>

      log.info(s"Voting for $candidate in $term")
      sender ! Vote(m.currentTerm)

      stay() using m.withVote(term, candidate)

    case Event(RequestVote(term, candidateId, lastLogTerm, lastLogIndex), m: Meta) =>
      log.info(s"Rejecting vote for $candidate, and $term, currentTerm: ${m.currentTerm}, already voted for: ${m.votes.get(term)}")
      sender ! Reject(m.currentTerm)
      stay()

    // end of election

    // take write
    case Event(msg: AppendEntries[Command], m: Meta) =>
      appendEntries(msg, m)

    // need to start an election
    case Event(ElectionTimeout(since), m: Meta) =>
      if (electionTimeoutStillValid(since))
        beginElection(m)
      else
        stay()

    case Event(AskForState, _) =>
      sender() ! IAmInState(Follower)
      stay()
  }

  def appendEntries(msg: AppendEntries[Command], m: Meta): State =
    if (leaderIsLagging(msg, m)) {
      if (msg.isNotHeartbeat) {
        log.info("Rejecting write (leader is lagging) of: " + msg + "; " + replicatedLog)
        leader ! AppendRejected(m.currentTerm, replicatedLog.lastIndex) // no need to respond if only heartbeat
      }
      stay()

    } else if (msg.isHeartbeat) {
      stayAcceptingHeartbeat()

    } else { //if (replicatedLog.containsMatchingEntry(msg.prevLogTerm, msg.prevLogIndex)) {
      log.info("Appending: " + msg.entries)
      println("leader = " + leader)
      leader ! append(msg.entries, msg.prevLogIndex, m)
      replicatedLog = commitUntilLeadersIndex(m, msg)

      stayAcceptingHeartbeat() using m.copy(
        currentTerm = replicatedLog.lastTerm
      )
    }
//    } else {
//      log.info("Rejecting write of (does not contain matching entry): " + msg + "; " + replicatedLog)
//      leader ! AppendRejected(m.currentTerm, replicatedLog.lastIndex)
//
//      stay()
//    }

  def leaderIsLagging(msg: AppendEntries[Command], m: Meta): Boolean =
    msg.term < m.currentTerm

  /**
   * @param atIndex is used to drop entries after this, and append our entries from there instead
   */
  def append(entries: immutable.Seq[Entry[Command]], atIndex: Int, m: Meta): AppendSuccessful = {
    replicatedLog = replicatedLog.append(entries, atIndex)
    log.info("log after append: " + replicatedLog.entries)

    AppendSuccessful(replicatedLog.lastTerm, replicatedLog.lastIndex)
  }
}
