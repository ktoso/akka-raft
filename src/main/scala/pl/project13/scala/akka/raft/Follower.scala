package pl.project13.scala.akka.raft

import scala.collection.immutable

import model._
import protocol._
import cluster.ClusterProtocol.{IAmInState, AskForState}

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
      log.info(s"Rejecting vote for ${candidate()}, and $term, currentTerm: ${m.currentTerm}, already voted for: ${m.votes.get(term)}")
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
        log.info("Rejecting write (Leader is lagging) of: " + msg + "; " + replicatedLog)
        leader ! AppendRejected(m.currentTerm, replicatedLog.lastIndex) // no need to respond if only heartbeat
      }
      stay()

    } else if (msg.isHeartbeat) {
      stayAcceptingHeartbeat()

    } else { //if (replicatedLog.containsMatchingEntry(msg.prevLogTerm, msg.prevLogIndex)) {
      log.info("Appending: " + msg.entries)
      leader ! append(msg.entries, m)
      replicatedLog = commitUntilLeadersIndex(m, msg)

      stayAcceptingHeartbeat() using m.copy(currentTerm = replicatedLog.lastTerm)
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
  def append(entries: immutable.Seq[Entry[Command]], m: Meta): AppendSuccessful = {
    val atIndex = entries.map(_.index).min
    log.debug("log before append: " + replicatedLog.entries)
    log.debug(bold("executing: " + s"replicatedLog = replicatedLog.append($entries, $atIndex)"))
    replicatedLog = replicatedLog.append(entries, atIndex)
    log.debug("log after append: " + replicatedLog.entries)

    AppendSuccessful(replicatedLog.lastTerm, replicatedLog.lastIndex)
  }
  
  def commitUntilLeadersIndex(m: Meta, msg: AppendEntries[Command]): ReplicatedLog[Command] = {
    val entries = replicatedLog.between(replicatedLog.committedIndex, msg.leaderCommitId)

    val handleAsSpecial = handleCommitIfSpecialEntry(m)
    val handleNormalEntry: PartialFunction[Any, Meta] = {
      case entry: Entry[Command] =>
        apply(entry.command)
        m
    }

    entries.foldLeft(replicatedLog) { case (repLog, entry) =>
      log.info(s"committing entry $entry on Follower, leader is committed until [${msg.leaderCommitId}]")
      log.info("entry = " + entry)

      handleAsSpecial.applyOrElse(entry, handleNormalEntry)

      repLog.commit(entry.index)
    }
  }

  private def handleCommitIfSpecialEntry(m: Meta): PartialFunction[Any, Meta] = {
    case Entry(jointConfig: JointConsensusRaftConfiguration, _, _, _) =>
      log.info("JointConsensusRaftConfiguration committed, will use it until new Configuration committed. " + jointConfig)
      m.copy(config = jointConfig)

    case Entry(stableConfig: StableRaftConfiguration, _, _, _) =>
     log.info("StableRaftConfiguration committed, finishing phase of cluster membership change. " + stableConfig)
      m.copy(config = stableConfig)
  }

  // todo remove me
  private def bold(msg: Any): String = Console.BOLD + msg.toString + Console.RESET

}
