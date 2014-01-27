package pl.project13.scala.akka.raft

import akka.actor.{Actor, ActorRef, LoggingFSM}
import scala.concurrent.duration._
import scala.util.Random
import scala.collection.immutable

import protocol._
import java.util.concurrent.TimeUnit

trait Raft extends LoggingFSM[RaftState, Metadata] with RaftStateMachine {
  this: Actor =>

  type Command <: AnyRef
  type Member = ActorRef

  private val config = context.system.settings.config

  val HeartbeatTimerName = "heartbeat-timer"
  val ElectionTimeoutTimerName = "election-timer"

  // must be included in leader's heartbeat
  def highestCommittedTermNr = Term.Zero

  // todo or move to Meta
  var replicatedLog = ReplicatedLog.empty[Command]

  val minElectionTimeout = config.getDuration("akka.raft.election-timeout.min", TimeUnit.MILLISECONDS).millis
  val maxElectionTimeout = config.getDuration("akka.raft.election-timeout.max", TimeUnit.MILLISECONDS).millis
  def nextElectionTimeout: FiniteDuration = randomElectionTimeout(
    from = minElectionTimeout,
    to = maxElectionTimeout
  )

  val heartbeatInterval: FiniteDuration = config.getDuration("akka.raft.heartbeat-interval", TimeUnit.MILLISECONDS).millis

  // todo so mutable or not......
  // todo or move to Meta
  var nextIndex = LogIndexMap.initialize(Vector.empty, replicatedLog.lastIndex)
  var matchIndex = LogIndexMap.initialize(Vector.empty, -1)

  override def preStart() {
    val timeout = resetElectionTimeout()
    log.info("Starting new Raft member. Initial election timeout: " + timeout)
  }

  startWith(Follower, Meta.initial)

  when(Follower) {
    
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
    case Event(ElectionTimeout, m: Meta) =>
      log.info(s"Election timeout reached")
      beginElection(m)
  }

  when(Candidate) {
    // election
    case Event(BeginElection, m: ElectionMeta) =>
      log.debug(s"Initializing election for ${m.currentTerm}")

      val request = RequestVote(m.currentTerm, self, replicatedLog.lastTerm, replicatedLog.lastIndex)
      m.membersExceptSelf foreach { _ ! request }

      stay() using m.incVote.withVoteFor(self)

    case Event(RequestVote(term, candidate, lastLogTerm, lastLogIndex), m: ElectionMeta)
      if m.canVoteIn(term) =>

      sender ! Vote(m.currentTerm)
      stay() using m.withVoteFor(candidate)

    case Event(RequestVote(term, candidate, lastLogTerm, lastLogIndex), m: ElectionMeta) =>
      sender ! Reject(term)
      stay()

    // todo if existing record, but is different value, rollback one value from log

    case Event(Vote(term), m: ElectionMeta) =>
      val includingThisVote = m.incVote

      if (includingThisVote.hasMajority) {
        log.info(s"Received vote from $voter; Won election with ${includingThisVote.votesReceived} of ${m.members.size} votes")
        goto(Leader) using m.forLeader
      } else {
        log.info(s"Received vote from $voter; Have ${includingThisVote.votesReceived} of ${m.members.size} votes")
        stay() using includingThisVote
      }

    case Event(Reject(term), m: ElectionMeta) =>
      log.debug(s"Rejected vote by $voter, in $term")
      stay()

    // end of election

//    // log appending -- todo step down and handle in Follower
//    case Event(AppendEntries(term, leader, prevLogIndex, prevLogTerm, entries), m) if term >= m.currentTerm =>
//      log.info("Got valid AppendEntries, falling back to Follower state and replaying message")
//      self forward AppendEntries(term, leader, prevLogIndex, prevLogTerm, entries)
//
//      goto(Follower)
//
//    case Event(AppendEntries(term, leader, prevLogIndex, prevLogTerm, entries: immutable.Seq[Entry[Command]]), m)
//        if isInconsistentTerm(m.currentTerm, term) =>
//      log.info(s"Got AppendEntries from stale $term, from $leader, not adding entries. AppendSuccessful.")
//      sender ! AppendSuccessful(m.currentTerm)
//
//      goto(Follower)

    case Event(append: AppendEntries[Entry[Command]], m: ElectionMeta) if append.term >= m.currentTerm =>
      log.info("Reverting to follower")
      goto(Follower) using m.forFollower

    // ending election due to timeout
    case Event(ElectionTimeout, m: ElectionMeta) =>
      log.debug("Voting timeout, starting a new election...")
      self ! BeginElection
      stay() using m.forNewElection
  }

  when(Leader) {
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

      log.info(s"entry = $entry")
      replicatedLog += entry
      log.info(s"log status = $replicatedLog")

      replicateLog(m)

      stay()

    case Event(AppendRejected(term), m: LeaderMeta) if term > m.currentTerm =>
      stopHeartbeat()
      stepDown(m) // since there seems to be another leader!

    case Event(msg: AppendRejected, m: LeaderMeta) =>
      registerAppendRejected(follower, msg, m)

    case Event(msg: AppendSuccessful, m: LeaderMeta) =>
      registerAppendSuccessful(follower, msg, m)
  }

  def sendEntries(follower: ActorRef, m: LeaderMeta) {
    val prevLogIndex = nextIndex.valueFor(follower)

    val entries = replicatedLog.entriesBatchFrom(prevLogIndex)
    log.debug("sendEntries::commands = " + entries)

    val msg = AppendEntries(
      m.currentTerm,
      prevLogIndex,
      replicatedLog.termAt(prevLogIndex),
      entries
    )

    log.info(s"Sending: $msg")

    follower ! msg
  }

  whenUnhandled {
    case Event(MembersChanged(newMembers), data: Meta) =>
      log.info(s"Members changed, current: ${data.members}, updating to: $newMembers")
      // todo migration period initiated here, right?
      stay() using data.copy(members = newMembers)
  }

  onTransition {
    case Follower -> Candidate =>
      self ! BeginElection
      resetElectionTimeout()

    case Candidate -> Leader =>
      self ! ElectedAsLeader()
      cancelElectionTimeout()

    case _ -> Follower =>
      resetElectionTimeout()
  }

  onTermination {
    case stop =>
      stopHeartbeat()
  }

  // helpers -----------------------------------------------------------------------------------------------------------

  def logIndex: Int = replicatedLog.lastIndex

  def initializeLeaderState(members: immutable.Seq[ActorRef]) {
    log.debug(s"Preparing nextIndex and matchIndex table for followers, init all to: replicatedLog.lastIndex = ${replicatedLog.lastIndex}")
    nextIndex = LogIndexMap.initialize(members, replicatedLog.lastIndex)
    matchIndex = LogIndexMap.initialize(members, -1)
  }

  def stopHeartbeat() {
    cancelTimer(HeartbeatTimerName)
  }

  def startHeartbeat(m: LeaderMeta) {
//  def startHeartbeat(currentTerm: Term, members: Vector[ActorRef]) {
    sendHeartbeat(m)
    log.info(s"Starting hearbeat, with interval: $heartbeatInterval")
    setTimer(HeartbeatTimerName, SendHeartbeat, heartbeatInterval, repeat = true)
  }

//  def sendHeartbeat(currentTerm: Term, members: Vector[ActorRef]) {
  def sendHeartbeat(m: LeaderMeta) {
    replicateLog(m)
//    members foreach { _ ! AppendEntries(currentTerm, replicatedLog.lastIndex, replicatedLog.lastTerm, Nil) }
  }

  def replicateLog(m: LeaderMeta) {
    m.others foreach { member =>
      val entries = replicatedLog.entriesBatchFrom(nextIndex.valueFor(member))
//      log.info(s"Sending: " + commands)
      val msg = AppendEntries(m.currentTerm, replicatedLog.prevIndex, replicatedLog.prevTerm, entries)

      member ! msg
    }
  }

  def cancelElectionTimeout() {
    cancelTimer(ElectionTimeoutTimerName)
  }

    def resetElectionTimeout(): FiniteDuration = {
    cancelTimer(ElectionTimeoutTimerName)

    val timeout = nextElectionTimeout
//    log.debug("Resetting election timeout: " + timeout)
    setTimer(ElectionTimeoutTimerName, ElectionTimeout, timeout, repeat = false)

    timeout
  }

  def appendEntries(msg: AppendEntries[Command], m: Meta) = msg.entries match {
//    case entries if entries.isEmpty =>
    case Nil =>
      stayAcceptingHeartbeat()

    case commands =>
      def logState() =
        log.info(s"Log state: ${replicatedLog.commands}")

      log.info(s"Got request to append: $msg; Follower's log state: ${replicatedLog.lastTerm} @ ${replicatedLog.lastIndex}")
      logState()

//      if(msg.term < m.currentTerm) {
//        // leader is behind
//        log.info(s"Append rejected - leader is behind (leader:${msg.term}, self: ${m.currentTerm})")
//        logState()
//
//        leader ! AppendRejected(m.currentTerm)
//        stayAcceptingHeartbeat()
//
//      } else
      if (replicatedLog.containsMatchingEntry(msg.prevLogTerm, msg.prevLogIndex)) {
        log.info("Appending entries: " + msg.entries)

        // todo make work with more
        msg.entries foreach { entry =>
          replicatedLog += entry
        }
        logState()

        leader ! AppendSuccessful(msg.term, replicatedLog.lastIndex)
        stayAcceptingHeartbeat() using m.copy(currentTerm = msg.term)

      } else {
        log.info("Append rejected.")
        logState()

        leader ! AppendRejected(m.currentTerm)
        stayAcceptingHeartbeat()
      }
    }

  def randomElectionTimeout(from: FiniteDuration = 150.millis, to: FiniteDuration = 300.millis): FiniteDuration = {
    val fromMs = from.toMillis
    val toMs = to.toMillis
    require(toMs > fromMs, s"to ($to) must be greater than from ($from) in order to create valid election timeout.")

    (fromMs + Random.nextInt(toMs.toInt - fromMs.toInt)).millis
  }

  // named state changes
  /** Start a new election */
  def beginElection(m: Meta) = {
    resetElectionTimeout()
    goto(Candidate) using m.forNewElection
  }

  /** Stop being the Leader */
  def stepDown(m: LeaderMeta) = goto(Follower) using m.forFollower

  /** Stay in current state and reset the election timeout */
  def stayAcceptingHeartbeat() = {
    resetElectionTimeout()
    stay()
  }

  def registerAppendRejected(member: ActorRef, msg: AppendRejected, m: LeaderMeta) = {
    val AppendRejected(followerTerm) = msg

    log.info(s"Follower $follower rejected write in term: $followerTerm, back out the first index in this term and retry")
    log.info(s"Leader log state: " + replicatedLog.entries)

    val indexToStartReplicationFrom = replicatedLog.firstIndexInTerm(followerTerm)
    nextIndex.put(follower, indexToStartReplicationFrom)

//    sendEntries(follower, m)

    stay()
  }
  def registerAppendSuccessful(member: ActorRef, msg: AppendSuccessful, m: LeaderMeta) = {
    val AppendSuccessful(followerTerm, followerIndex) = msg

    // update our tables for this member
    nextIndex.put(follower, followerIndex)
    matchIndex.putIf(follower, _ < _, nextIndex.valueFor(follower))
    log.info(s"Follower $follower took write in term: $followerTerm, index: ${nextIndex.valueFor(follower)}")

    nextIndex.incrementFor(follower)

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

  def maybeCommitEntry(matchIndex: LogIndexMap, replicatedLog: ReplicatedLog[Command]): ReplicatedLog[Command] = {
    val indexOnMajority = matchIndex.indexOnMajority
    log.debug("Majority of members have index: " + indexOnMajority + " persisted. (Comitted index: " + replicatedLog.commitedIndex + ")")

    if (indexOnMajority > replicatedLog.commitedIndex) {
      val entries = replicatedLog.between(replicatedLog.commitedIndex, indexOnMajority)

      entries foreach { entry =>
        log.info(s"Comitting entry at index: ${entry.index}; Applying command: ${entry.command}, will send result to client: ${entry.client}")

        val result = apply(entry.command)
        entry.client map { _ ! result }
      }

      replicatedLog.commit(indexOnMajority)
    } else {
      replicatedLog
    }
  }

  /** `true` if this follower is at `Term(2)`, yet the incoming term is `t > Term(3)` */
  def isInconsistentTerm(currentTerm: Term, term: Term): Boolean = term < currentTerm

  // sender aliases, for readability
  @inline def follower = sender()
  @inline def candidate = sender()
  @inline def leader = sender()

  @inline def voter = sender() // not explicitly a Raft role, but makes it nice to read
  // end of sender aliases

  private def bold(msg: Any): String = Console.BOLD + msg.toString + Console.RESET
}
