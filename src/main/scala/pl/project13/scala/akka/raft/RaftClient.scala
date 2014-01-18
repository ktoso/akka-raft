package pl.project13.scala.akka.raft

import akka.actor.{Actor, ActorRef, LoggingFSM}
import scala.concurrent.duration._
import scala.util.{Success, Random}
import scala.concurrent.Future
import scala.collection.immutable
import scala.collection.mutable

import protocol._

trait RaftClient extends LoggingFSM[RaftState, Message[_]] {
  this: Actor =>

  import context.dispatcher

  type Command <: AnyRef
  type Commands = immutable.Seq[Command]

  // user-land API -----------------------------------------------------------------------------------------------------
  /**
   * Raft must know about all nodes participating, if these change (node joins cluster),
   * you should reflect this in the returned value here.
   */
  def allNodes: immutable.Seq[ActorRef]

  /** Called when a command is determined by Raft to be safe to apply */
  def apply(command: Command): Unit
  // end of user-land API -----------------------------------------------------------------------------------------------------


  // must be included in leader's heartbeat
  def highestCommittedTermNr: Term = Term.Zero

  val replicatedLog = Log.empty[Command]

  val electionTimeout: FiniteDuration = randomElectionTimeout(from = 150.millis, to = 300.millis)

  val electingTimeout: Duration = 4.seconds

  var currentTerm = Term.Zero

  // todo could be maps
  val nextIndex = mutable.HashMap[ActorRef, Int]()
  val matchIndex = mutable.HashMap[ActorRef, Int]()

  startWith(Follower, AppendEntries(currentTerm, Nil))

  when(Follower, stateTimeout = electionTimeout) {
    case Event(RequestVote(term, candidateId, lastLogTerm, lastLogIndex), data) if isCurrentOrLater(lastLogTerm, lastLogIndex) =>
      stay()

    case Event(RequestVote(term, cancicateId, lastLogTerm, lastLogIndex), data)=>
      // todo wrong?
      nextTerm()
      goto(Candidate) using RequestVote(currentTerm, self, Term(0), 0) forMax electingTimeout // todo

      stay()

    case Event(AppendEntries(term, entries: Entries[Command], votedFor), data) =>
      appendEntriesConsistencyCheck(entries)
      stay()
  }

  when(Candidate) {

    case Event(AppendEntries(termNr, _, _), data) if isCurrentOrLaterTerm(termNr) =>
      goto(Follower) // todo using

    case Event(AppendEntries(termNr, _, _), data) if isStaleTerm(termNr) =>
      log.info(s"Got AppendEntries from stale termNr: $termNr, from: ${sender()}, ignoring.")
      stay()

    // ending election
    case Event(ElectedLeader(), data) =>
      goto(Follower) // todo using???

//    todo get's Vote, and number of those is > 1/2 cluster size
//    case I getElected =>
//      goto(Leader)

    case Event(StateTimeout, data) =>
      ???
  }

  when(Leader) {
    case Event(ElectedAsLeader(), data) =>
      // initialize
      prepareEachFollowersNextIndex(allNodes)
      stay()

//  todo probably not needed
    case Event(AppendSuccessful, data) =>
      stay()

    case Event(AppendRejected, data) =>
      val logIndexToSend = decrementNextIndexFor(follower)

      // todo continue sending to follower, to make it catch up
      // todo should include term a command came from, right?
      follower ! AppendEntries(currentTerm, replicatedLog.entriesFrom(logIndexToSend))

      stay()
  }

  // helpers -----------------------------------------------------------------------------------------------------------

  def logIndex: Int = replicatedLog.lastIndex

  def isCurrentOrLater(term: Term, index: Int): Boolean = {
    ???
  }

  /** as reaction to a follower not being able to take a write. */
  def decrementNextIndexFor(follower: ActorRef): Int = {
    val newValue = nextIndex(follower) - 1
    nextIndex(follower) = newValue
    newValue
  }

  def appendEntriesConsistencyCheck(appendEntries: Seq[Entry[Command]]) {
    ???
  }
  
  def prepareEachFollowersNextIndex(followers: immutable.Seq[ActorRef]) {
    nextIndex.clear()
    val lastIndexOnLeader = replicatedLog.commitedIndex
    followers foreach { follower => nextIndex(follower) = lastIndexOnLeader }
  }

  def appendToLog(x: Any): Future[Unit] = {
    // todo send to all, await safe write

    Future()
  }

  def randomElectionTimeout(from: FiniteDuration = 150.millis, to: FiniteDuration = 300.millis): FiniteDuration = {
    val fromMs = from.toMillis
    val toMs = to.toMillis
    require(toMs > fromMs, s"to ($to) must be greater than from ($from) in order to create valid election timeout.")

    (fromMs + Random.nextInt(toMs.toInt - fromMs.toInt)).millis
  }



  def isStaleTerm(term: Term): Boolean = term < currentTerm
  def isCurrentOrLaterTerm(term: Term): Boolean = term >= currentTerm
  def nextTerm(): Unit = currentTerm = currentTerm + 1

  // sender aliases, for readability
  @inline def follower = sender()
  @inline def candidate = sender()
  @inline def leader = sender()
  // end of sender aliases
}
