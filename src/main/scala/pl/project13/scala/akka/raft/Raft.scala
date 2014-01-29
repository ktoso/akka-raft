package pl.project13.scala.akka.raft

import akka.actor.{Actor, ActorRef, LoggingFSM}
import scala.concurrent.duration._
import scala.util.Random
import scala.collection.immutable

import protocol._
import java.util.concurrent.TimeUnit

trait Raft[Command] extends LoggingFSM[RaftState, Metadata] with RaftStateMachine[Command] {
  this: Actor =>

  type Member = ActorRef

  private val config = context.system.settings.config

  val HeartbeatTimerName = "heartbeat-timer"
  val ElectionTimeoutTimerName = "election-timer"

  // todo fail much, so lol
  var electionTimeoutDieOn = 0L

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

  // todo or move to Meta
  var nextIndex = LogIndexMap.initialize(Vector.empty, replicatedLog.lastIndex)
  // todo or move to Meta
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
    case Event(ElectionTimeout(since), m: Meta) =>
      if (electionTimeoutStillValid(since))
        beginElection(m)
      else
        stay()
  }

  when(Candidate) {
    // election
    case Event(BeginElection, m: ElectionMeta) =>
      log.debug(s"Initializing election for ${m.currentTerm}")

      val request = RequestVote(m.currentTerm, self, replicatedLog.lastTerm, replicatedLog.lastIndex)
      m.membersExceptSelf foreach { _ ! request }

      val includingThisVote = m.incVote
      stay() using includingThisVote.withVoteFor(m.currentTerm, self)

    case Event(msg: RequestVote, m: ElectionMeta) if m.canVoteIn(msg.term) =>
      log.info(s"term >= currentTerm && votes.get(term).isEmpty == ${msg.term} >= ${m.currentTerm} && ${m.votes.get(msg.term).isEmpty}")
      sender ! Vote(m.currentTerm)
      stay() using m.withVoteFor(msg.term, candidate)

    case Event(msg: RequestVote, m: ElectionMeta) =>
      sender ! Reject(msg.term)
      stay()

    case Event(Vote(term), m: ElectionMeta) =>
      val includingThisVote = m.incVote

      if (includingThisVote.hasMajority) {
        log.info(s"Received vote by $voter; Won election with ${includingThisVote.votesReceived} of ${m.members.size} votes")
        goto(Leader) using m.forLeader
      } else {
        log.info(s"Received vote by $voter; Have ${includingThisVote.votesReceived} of ${m.members.size} votes")
        stay() using includingThisVote
      }

    case Event(Reject(term), m: ElectionMeta) =>
      log.debug(s"Rejected vote by $voter, in $term")
      stay()

    // end of election

    case Event(append: AppendEntries[Entry[Command]], m: ElectionMeta) if append.term >= m.currentTerm =>
      log.info("Reverting to follower")
      goto(Follower) using m.forFollower

    //     todo step down and handle in Follower
    case Event(append: AppendEntries[Entry[Command]], m: ElectionMeta) =>
      log.info("AAAA!")
      self ! append
      goto(Follower)

    // ending election due to timeout
    case Event(ElectionTimeout(since), m: ElectionMeta) =>
      log.debug(s"Voting timeout, starting a new election... (since: ${since})")
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
  }

  def sendEntries(follower: Member, m: LeaderMeta) {
    follower ! AppendEntries(
      m.currentTerm,
      replicatedLog,
      fromIndex = nextIndex.valueFor(follower),
      leaderCommitId = replicatedLog.committedIndex
    )
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

  initialize() // akka internals, MUST be last call in constructor

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

  /** heartbeat is implemented as basically sending AppendEntry messages */
  def sendHeartbeat(m: LeaderMeta) {
    replicateLog(m)
  }

  def replicateLog(m: LeaderMeta) {
    m.others foreach { member =>
      log.info(s"""sending : ${AppendEntries(
              m.currentTerm,
              replicatedLog,
              fromIndex = nextIndex.valueFor(member),
              leaderCommitId = replicatedLog.committedIndex
            )} to $member""")

      member ! AppendEntries(
        m.currentTerm,
        replicatedLog,
        fromIndex = nextIndex.valueFor(member),
        leaderCommitId = replicatedLog.committedIndex
      )
    }
  }

  def cancelElectionTimeout() {
    cancelTimer(ElectionTimeoutTimerName)
  }

    def resetElectionTimeout(): FiniteDuration = {
    cancelTimer(ElectionTimeoutTimerName)

    val timeout = nextElectionTimeout
    val since = System.currentTimeMillis()
    log.debug(s"Resetting election timeout: $timeout (since:$since)")

    electionTimeoutDieOn = since + timeout.toMillis
    setTimer(ElectionTimeoutTimerName, ElectionTimeout(since), timeout, repeat = false)

    timeout
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
    log.debug("log after append: " + replicatedLog.entries)

    AppendSuccessful(replicatedLog.lastTerm, replicatedLog.lastIndex)
  }

  private def randomElectionTimeout(from: FiniteDuration = 150.millis, to: FiniteDuration = 300.millis): FiniteDuration = {
    val fromMs = from.toMillis
    val toMs = to.toMillis
    require(toMs > fromMs, s"to ($to) must be greater than from ($from) in order to create valid election timeout.")

    (fromMs + Random.nextInt(toMs.toInt - fromMs.toInt)).millis
  }

  @inline private def electionTimeoutStillValid(since: Long) = {
    val stillValid = electionTimeoutDieOn < System.currentTimeMillis()

    if (stillValid)
      log.info(s"Timeout reached (since: $since, ago: ${System.currentTimeMillis() - since})")

    stillValid
  }

  // named state changes
  /** Start a new election */
  def beginElection(m: Meta) = {
    resetElectionTimeout()
    log.info("Beginning new election")

    goto(Candidate) using m.forNewElection forMax nextElectionTimeout
  }

  /** Stop being the Leader */
  def stepDown(m: LeaderMeta) = goto(Follower) using m.forFollower

  /** Stay in current state and reset the election timeout */
  def stayAcceptingHeartbeat() = {
    resetElectionTimeout()
    stay()
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

  def commitUntilLeadersIndex(m: Meta, msg: AppendEntries[Command]): ReplicatedLog[Command] = {
    val entries = replicatedLog.between(replicatedLog.committedIndex, msg.leaderCommitId)

    entries.foldLeft(replicatedLog) { case (repLog, entry) =>
      log.info(s"committing entry $entry on Follower, leader is committed until [${msg.leaderCommitId}}]")
      apply(entry.command)

      repLog.commit(entry.index)
    }
  }

  def maybeCommitEntry(matchIndex: LogIndexMap, replicatedLog: ReplicatedLog[Command]): ReplicatedLog[Command] = {
    val indexOnMajority = matchIndex.indexOnMajority
    val willCommit = indexOnMajority > replicatedLog.committedIndex
    log.debug(s"Majority of members have index: $indexOnMajority persisted. (Comitted index: ${replicatedLog.committedIndex}, will commit now: $willCommit)")

    if (willCommit) {
      val entries = replicatedLog.between(replicatedLog.committedIndex, indexOnMajority)
      log.info(s"Before commit; indexOnMajority:$indexOnMajority, replicatedLog.committedIndex: ${replicatedLog.committedIndex} => entries = $entries")

      entries foreach { entry =>
        log.info(s"Committing log at index: ${entry.index}; Applying command: ${entry.command}, will send result to client: ${entry.client}")

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
