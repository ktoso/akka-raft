package pl.project13.scala.akka.raft

import akka.actor.{Actor, ActorRef, LoggingFSM}
import scala.concurrent.duration._
import scala.util.Random
import scala.concurrent.Future
import scala.collection.immutable

import protocol._

trait Raft extends LoggingFSM[RaftState, Metadata] {
  this: Actor =>

  import context.dispatcher

  type Command <: AnyRef
  type Commands = immutable.Seq[Command]
  type Member = ActorRef

  // user-land API -----------------------------------------------------------------------------------------------------
  /** Called when a command is determined by Raft to be safe to apply */
  def apply(command: Command): Unit
  // end of user-land API -----------------------------------------------------------------------------------------------------


  // must be included in leader's heartbeat
  def highestCommittedTermNr: Term = Term.Zero

  val replicatedLog = ReplicatedLog.empty[Command]

  def electionTimeout = randomElectionTimeout(from = 150.millis, to = 300.millis)
  
  val heartbeatInterval = (150 + (electionTimeout.toMillis - 150 / 2)).millis

  var currentTerm = Term.Zero

  /** Needs to be on each Term change */
  var votedFor: ActorRef = null

  // todo could be maps
  var nextIndex = LogIndexMap.initialize(Vector.empty, replicatedLog.lastIndex)
  var matchIndex = LogIndexMap.initialize(Vector.empty, 0)

  startWith(Follower, Meta.initial)

  when(Follower, stateTimeout = electionTimeout) {
    
    // election
    case Event(RequestVote(term, candidate, lastLogTerm, lastLogIndex), m: Meta) if m.canVote && term > currentTerm =>
      log.info(s"Voting for $candidate in $term")
      sender ! Vote(currentTerm)
      stay()

    case Event(RequestVote(term, candidateId, lastLogTerm, lastLogIndex), m: Meta) =>
      sender ! Reject(currentTerm)
      stay()
      
    // end of election

    // append entries
    case Event(AppendEntries(term, leader, prevLogIndex, prevLogTerm, entries: Entries[Command]), data)
      if replicatedLog.isConsistentWith(prevLogIndex, prevLogTerm) =>

      stay() forMax electionTimeout

    // need to start an election
    case Event(StateTimeout, data: Meta) =>
      log.info(s"Election timeout reached")

      self ! BeginElection
      goto(Candidate) using data.forNewElection
  }

  when(Candidate, stateTimeout = electionTimeout) {
    // election
    case Event(BeginElection, data: ElectionMeta) =>
      currentTerm = currentTerm + 1
      log.debug(s"Initializing election for $currentTerm")

      val request = RequestVote(currentTerm, self, replicatedLog.lastTerm, replicatedLog.lastIndex)
      data.membersExceptSelf foreach { _ ! request }

      stay() using data.withOneMoreVote forMax electionTimeout

    case Event(RequestVote(term, candidate, lastLogTerm, lastLogIndex), m: ElectionMeta) if term < currentTerm => {
      sender ! Reject
      stay()
    }
    
    case Event(RequestVote(term, candidate, lastLogTerm, lastLogIndex), m: ElectionMeta) if m.canVote => {
      sender ! Vote(currentTerm)
      stay()
    }

    case Event(Vote(term), data: ElectionMeta) if data.withOneMoreVote.hasMajority =>
      log.info(s"Received enough votes to be majority (${data.withOneMoreVote.votesReceived} of ${data.members.size})")
      goto(Leader) using data.forLeader

    case Event(Vote(term), data: ElectionMeta) =>
      log.info(s"Received vote from $voter, now up to ${data.withOneMoreVote.votesReceived}")
      stay() using data.withOneMoreVote

    case Event(Reject(term), m: ElectionMeta) =>
      log.debug(s"Rejected vote by $voter, in $term")
      stay()

    // end of election

    // log appending -- todo step down and handle in Follower
    case Event(AppendEntries(term, leader, prevLogIndex, prevLogTerm, entries), m) if term >= currentTerm =>
      log.info("Got valid AppendEntries, falling back to Follower state and replaying message")
      self ! AppendEntries(term, leader, prevLogIndex, prevLogTerm, entries)
      goto(Follower) forMax electionTimeout

    // append for Term from too far in the future
    case Event(AppendEntries(term, leader, prevLogIndex, prevLogTerm, entries: Entries[Command]), data) if isInconsistentTerm(term) =>
      log.info(s"Got AppendEntries from stale $term, from $leader, not adding entries. AppendSuccessful.")
      sender ! AppendSuccessful(currentTerm)
      goto(Follower) forMax electionTimeout

    // ending election due to timeout
    case Event(StateTimeout, data: ElectionMeta) =>
      log.debug("Voting timeout, starting a new election...")
      self ! BeginElection
      stay() using data.forFollower.forNewElection forMax electionTimeout
  }

  when(Leader) {
    case Event(ElectedAsLeader, m: LeaderMeta) =>
      prepareEachFollowersNextIndex(m.others)
      sendHeartbeat(m.others)
      setTimer("hearbeat-timer", SendHeartbeat, heartbeatInterval, repeat = true)
      stay()

    case Event(SendHeartbeat, m: LeaderMeta) =>
      sendHeartbeat(m.others)
      stay()

    case Event(AppendSuccessful(term), _) =>
      // todo probably not needed
      stay()

    case Event(AppendRejected(term), m: LeaderMeta) if term > currentTerm =>
      stepDown(m) // since there seems to be another leader!

    case Event(AppendRejected(term), m: LeaderMeta) =>
      nextIndex = nextIndex.decrementFor(follower)
      val logIndexToSend = nextIndex.valueFor(follower)

      // todo continue sending to follower, to make it catch up
      // todo should include term a command came from, right?
      follower ! AppendEntries(currentTerm, self, replicatedLog.lastIndex, replicatedLog.lastTerm, replicatedLog.entriesFrom(logIndexToSend))

      stay()
  }

  whenUnhandled {
    case Event(MembersChanged(newMembers), data: Meta) =>
      log.info(s"Members changed, current: ${data.members}, updating to: $newMembers")
      // todo migration period initiated here, right?
      stay() using data.copy(members = newMembers)
  }

  onTransition {
    case Candidate -> Leader =>
      self ! ElectedAsLeader
  }

  // helpers -----------------------------------------------------------------------------------------------------------

  def logIndex: Int = replicatedLog.lastIndex

  def prepareEachFollowersNextIndex(followers: immutable.Seq[ActorRef]) {
    nextIndex = LogIndexMap.initialize(followers, replicatedLog.lastIndex)
  }

  def sendHeartbeat(members: Vector[ActorRef]) {
    members foreach { _ ! AppendEntries(currentTerm, self, replicatedLog.lastIndex, replicatedLog.lastTerm, Nil) }
  }

  def appendToLog(x: Any): Future[Unit] = {
    // todo send to all, await safe write
    ???
    Future()
  }

  def randomElectionTimeout(from: FiniteDuration = 150.millis, to: FiniteDuration = 300.millis): FiniteDuration = {
    val fromMs = from.toMillis
    val toMs = to.toMillis
    require(toMs > fromMs, s"to ($to) must be greater than from ($from) in order to create valid election timeout.")

    (fromMs + Random.nextInt(toMs.toInt - fromMs.toInt)).millis
  }

  // named state changes
  /** Stop being the Leader */
  def stepDown(m: LeaderMeta) = goto(Follower) using m.forFollower



  def isStaleTerm(term: Term): Boolean = term < currentTerm
  /** `true` if this follower is at `Term(2)`, yet the incoming term is `t > Term(3)` */
  def isInconsistentTerm(term: Term): Boolean = term < currentTerm
  def isCurrentOrLaterTerm(term: Term): Boolean = term >= currentTerm
  def nextTerm(): Unit = currentTerm = currentTerm + 1

  // sender aliases, for readability
  @inline def follower = sender()
  @inline def candidate = sender()
  @inline def leader = sender()

  @inline def voter = sender() // not explicitly a Raft role, but makes it nice to read
  // end of sender aliases
}
