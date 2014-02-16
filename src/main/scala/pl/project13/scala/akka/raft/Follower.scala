package pl.project13.scala.akka.raft

import scala.collection.immutable

import model._
import protocol._
import config.RaftConfig
import akka.actor.ActorRef
import scala.annotation.tailrec

private[raft] trait Follower {
  this: RaftActor =>

  protected def raftConfig: RaftConfig

  val followerBehavior: StateFunction = {
    case Event(msg: ClientMessage[Command], m: Meta) =>
      log.info("Follower got {} from client; Respond with last Leader that took write from: {}", msg, recentlyContactedByLeader)
      sender() ! LeaderIs(recentlyContactedByLeader, Some(msg))
      stay()

    // election
    case Event(RequestVote(term, candidate, lastLogTerm, lastLogIndex), m: Meta)
      if m.canVoteIn(term) =>

      log.info("Voting for {} in {}", candidate, term)
      sender ! VoteCandidate(m.currentTerm)

      stay() using m.withVote(term, candidate)

    case Event(RequestVote(term, candidateId, lastLogTerm, lastLogIndex), m: Meta) =>
      log.info("Rejecting vote for {}, and {}, currentTerm: {}, already voted for: {}", candidate(), term, m.currentTerm, m.votes.get(term))
      sender ! DeclineCandidate(m.currentTerm)
      stay()

    // end of election

    // take writes
    case Event(msg: AppendEntries[Command], m: Meta) =>
      senderIsCurrentLeader()
      appendEntries(msg, m)

    // end of take writes

    // timeout, may need to start an election
    case Event(ElectionTimeout, m: Meta) =>
      if (electionDeadline.isOverdue()) beginElection(m)
      else stay()

    case Event(AskForState, _) =>
      sender() ! IAmInState(Follower)
      stay()
  }
  

  def appendEntries(msg: AppendEntries[Command], m: Meta): State = {
    implicit val self = m.clusterSelf // todo this is getting pretty crap, revert to having Cluster awareness a trait IMO

    if (leaderIsLagging(msg, m)) {
      if (msg.isNotHeartbeat) {
        log.info("Rejecting write (Leader is lagging) of: " + msg + "; " + replicatedLog)
        leader ! AppendRejected(m.currentTerm, replicatedLog.lastIndex) // no need to respond if only heartbeat
      }
      stay()

    } else if (msg.isHeartbeat) {
      acceptHeartbeat()

    } else {
      log.debug("Appending: " + msg.entries)
      leader ! append(msg.entries, m)
      replicatedLog = commitUntilLeadersIndex(m, msg)
      
      val meta = maybeUpdateConfiguration(m, msg.entries.map(_.command))
      val metaWithUpdatedTerm = meta.copy(currentTerm = replicatedLog.lastTerm)
      acceptHeartbeat() using metaWithUpdatedTerm
    }
  }

  def leaderIsLagging(msg: AppendEntries[Command], m: Meta): Boolean =
    msg.term < m.currentTerm

  def append(entries: immutable.Seq[Entry[Command]], m: Meta): AppendSuccessful = {
    val atIndex = entries.map(_.index).min
    log.debug("executing: replicatedLog = replicatedLog.append({}, {})", entries, atIndex)

    replicatedLog = replicatedLog.append(entries, atIndex)
//    log.debug("log after append: " + replicatedLog.entries)
    AppendSuccessful(replicatedLog.lastTerm, replicatedLog.lastIndex)
  }

  /**
   * Configurations must be used by each node right away when they get appended to their logs (doesn't matter if not committed).
   * This method updates the Meta object if a configuration change is discovered.
   */
  @tailrec final def maybeUpdateConfiguration(meta: Meta, entries: Seq[Command]): Meta = entries match {
    case Nil =>
      meta

    case (newConfig: ClusterConfiguration) :: moreEntries if newConfig.isNewerThan(meta.config) =>
      log.info("Appended new configuration (seq: {}), will start using it now: {}", newConfig.sequenceNumber, newConfig)
      maybeUpdateConfiguration(meta.withConfig(newConfig), moreEntries)

    case _ :: moreEntries =>
      maybeUpdateConfiguration(meta, moreEntries)
  }
  
  def commitUntilLeadersIndex(m: Meta, msg: AppendEntries[Command]): ReplicatedLog[Command] = {
    val entries = replicatedLog.between(replicatedLog.committedIndex, msg.leaderCommitId)

    entries.foldLeft(replicatedLog) { case (repLog, entry) =>
      log.debug("committing entry {} on Follower, leader is committed until [{}]", entry, msg.leaderCommitId)

      handleCommitIfSpecialEntry.applyOrElse(entry, handleNormalEntry)

      repLog.commit(entry.index)
    }
  }

  private def senderIsCurrentLeader(): Unit =
    recentlyContactedByLeader = Some(sender())
  
  private val handleNormalEntry: PartialFunction[Any, Unit] = {
    case entry: Entry[Command] => apply(entry.command)
  }

  private val handleCommitIfSpecialEntry: PartialFunction[Any, Unit] = {
    case Entry(jointConfig: ClusterConfiguration, _, _, _) =>
      // simply ignore applying cluster configurations onto the client state machine,
      // it's an internal thing and the client does not care about cluster config change.
  }
  
}
