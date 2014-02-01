package pl.project13.scala.akka.raft

import akka.actor.{Actor, ActorRef, LoggingFSM}
import scala.concurrent.duration._

import protocol._
import pl.project13.scala.akka.raft.model.{Entry, ReplicatedLog, Term, LogIndexMap}
import pl.project13.scala.akka.raft.protocol.RaftStates._
import pl.project13.scala.akka.raft.cluster.ClusterProtocol.{IAmInState, AskForState}


private[raft] trait Leader {
  this: RaftActor =>

  val leaderBehavior: StateFunction = {
    case Event(ElectedAsLeader(), m: LeaderMeta) =>
      log.info(bold(s"Became leader for ${m.currentTerm}"))
      initializeLeaderState(m.members)
      startHeartbeat(m)
      stay()

    case Event(SendHeartbeat, m: LeaderMeta) =>
      sendHeartbeat(m)
      stay()

    // already won election, but votes may still be coming in
    case Event(_: ElectionMessage, _) =>
      stay()

    // client request
    case Event(ClientMessage(client, cmd: Command), m: LeaderMeta) =>
      log.info(s"Appending command: [${bold(cmd)}] from $client to replicated log...")

      val entry = Entry(cmd, m.currentTerm, replicatedLog.nextIndex, Some(client))

      log.info(s"adding to log: $entry")
      replicatedLog += entry
      log.info(s"log status = $replicatedLog")

      replicateLog(m)

      stay()

    case Event(AppendRejected(term, index), m: LeaderMeta) if term > m.currentTerm =>
      stopHeartbeat()
      stepDown(m) // since there seems to be another leader!

    case Event(msg: AppendRejected, m: LeaderMeta) =>
      registerAppendRejected(follower, msg, m)

    case Event(msg: AppendSuccessful, m: LeaderMeta) =>
      registerAppendSuccessful(follower, msg, m)

    case Event(AskForState, _) =>
      sender() ! IAmInState(Leader)
      stay()
  }

  def initializeLeaderState(members: Set[Member]) {
    log.info(s"Preparing nextIndex and matchIndex table for followers, init all to: replicatedLog.lastIndex = ${replicatedLog.lastIndex}")
    nextIndex = LogIndexMap.initialize(members, replicatedLog.lastIndex)
    matchIndex = LogIndexMap.initialize(members, -1)
  }

  def sendEntries(follower: Member, m: LeaderMeta) {
    follower ! AppendEntries(
      m.currentTerm,
      replicatedLog,
      fromIndex = nextIndex.valueFor(follower),
      leaderCommitId = replicatedLog.committedIndex
    )
  }

  def registerAppendRejected(member: ActorRef, msg: AppendRejected, m: LeaderMeta) = {
    val AppendRejected(followerTerm, followerIndex) = msg

    log.info(s"Follower $follower rejected write: $followerTerm @ $followerIndex, back out the first index in this term and retry")
    log.info(s"Leader log state: " + replicatedLog.entries)

    nextIndex.putIfSmaller(follower, followerIndex)

//    todo think if we send here or keep in heartbeat
    sendEntries(follower, m)

    stay()
  }
  def registerAppendSuccessful(member: ActorRef, msg: AppendSuccessful, m: LeaderMeta) = {
    log.info(s"Follower $follower accepted write")
    val AppendSuccessful(followerTerm, followerIndex) = msg

    // update our tables for this member
    nextIndex.put(follower, followerIndex)
    matchIndex.putIfGreater(follower, nextIndex.valueFor(follower))
    log.info(s"Follower $follower took write in term: $followerTerm, index: ${nextIndex.valueFor(follower)}")

//    nextIndex.incrementFor(follower)

//    sendOldEntriesIfLaggingClient(followerIndex, m)

    replicatedLog = maybeCommitEntry(matchIndex, replicatedLog)

    stay()
  }


  def sendOldEntriesIfLaggingClient(followerIndex: Int, m: LeaderMeta) {
    if (followerIndex < replicatedLog.lastIndex) {
      log.info(s"$followerIndex < ${replicatedLog.lastIndex}")
      sendEntries(follower, m)
    }
  }

  // todo remove me
  private def bold(msg: Any): String = Console.BOLD + msg.toString + Console.RESET

}