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
    log.debug("Initial election timeout: " + timeout)
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
      log.info(s"Rejecting vote for $candidate, and $term, currentTerm: ${m.currentTerm}, already voted for: ${m.votes}")
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

      val voteForMyself = m.incVote
      stay() using voteForMyself

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
      startHeartbeat(m.currentTerm, m.others)
      stay()

    case Event(SendHeartbeat, m: LeaderMeta) =>
      sendHeartbeat(m.currentTerm, m.others)
      stay()

    // already won election, but votes may still be coming in
    case Event(_: ElectionMessage, _) =>
      stay()

    // client request
    case Event(ClientMessage(client, cmd: Command), m: LeaderMeta) =>
      log.info(s"Appending command: [${bold(cmd)}] from $client to replicated log...")

      replicatedLog += Entry(cmd, m.currentTerm, replicatedLog.lastIndex + 1, Some(client))
      self ! AppendSuccessful(m.currentTerm, replicatedLog.lastIndex)

      replicateLog(m)

      stay()

    case Event(AppendSuccessful(term, idx), m: LeaderMeta) =>
      nextIndex.put(follower, idx)
      matchIndex.putIf(follower, _ < _, nextIndex.valueFor(follower))

      val comittedIndex = matchIndex.indexOnMajority

      log.info(s"Follower $follower took write in term: $term, index: ${nextIndex.valueFor(follower)}")

      if (idx < replicatedLog.lastIndex) {
        log.info(s"$idx < ${replicatedLog.lastIndex}")
        sendEntries(follower, m)
      }

      if (comittedIndex > replicatedLog.commitedIndex) {
        replicatedLog = replicatedLog.commit(comittedIndex)
        val entry = replicatedLog(comittedIndex)
        log.info(s"Applying command: $entry")
        apply(entry.command)
      }

      stay()

    case Event(AppendRejected(term), m: LeaderMeta) if term <= m.currentTerm =>
      log.info(s"Follower $follower rejected write in term: $term, back out one index and retry")

      val indexToStartReplicationFrom = replicatedLog.firstIndexInTerm(term)
      nextIndex.put(follower, indexToStartReplicationFrom)

      sendEntries(follower, m)

      stay()

    case Event(AppendRejected(term), m: LeaderMeta) if term > m.currentTerm =>
      stopHeartbeat()
      stepDown(m) // since there seems to be another leader!
  }

  def sendEntries(follower: ActorRef, m: LeaderMeta) {
    val prevLogIndex = nextIndex.valueFor(follower)

    val commands = replicatedLog.commandsBatchFrom(prevLogIndex + 1, 1)
    val msg = AppendEntries(
      m.currentTerm,
      prevLogIndex,
      replicatedLog.termAt(prevLogIndex),
      commands
    )

    log.info(s"Sending: ${msg}")

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

  def startHeartbeat(currentTerm: Term, members: Vector[ActorRef]) {
    sendHeartbeat(currentTerm, members)
    log.info(s"Starting hearbeat, with interval: $heartbeatInterval")
    setTimer(HeartbeatTimerName, SendHeartbeat, heartbeatInterval, repeat = true)
  }

  def sendHeartbeat(currentTerm: Term, members: Vector[ActorRef]) {
    members foreach { _ ! AppendEntries(currentTerm, replicatedLog.lastIndex, replicatedLog.lastTerm, Nil) }
  }

  def replicateLog(m: LeaderMeta) {
    m.others foreach { member =>
      log.info(s"nextIndex.valueFor(${member.path.elements.last}) = " + nextIndex.valueFor(member))
      val commands = replicatedLog.commandsBatchFrom(nextIndex.valueFor(member))
      val msg = AppendEntries(m.currentTerm, replicatedLog.prevIndex, replicatedLog.prevTerm, commands)

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

  def appendEntries(msg: AppendEntries[Command], m: Meta) = {
    def logState() =
      log.info(s"Log state: ${replicatedLog.commands}")

    if(msg.term < m.currentTerm) {
      // leader is behind
      log.info(s"Append rejected - leader is behind (leader:${msg.term}, self: ${m.currentTerm})")
      logState()

      leader ! AppendRejected(m.currentTerm)
    } else if (replicatedLog.containsMatchingEntry(msg.prevLogTerm, msg.prevLogIndex)) {
      // matches, do append
      log.info("entries = " + msg.entries)

      msg.entries foreach { entry =>
        replicatedLog += entry
      }
      logState()

      leader ! AppendSuccessful(msg.term, replicatedLog.lastIndex)
    } else {
      log.info("Append rejected.")
      logState()

      leader ! AppendRejected(m.currentTerm)
    }

    stayAcceptingHeartbeat() using m.copy(currentTerm = msg.term)
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
