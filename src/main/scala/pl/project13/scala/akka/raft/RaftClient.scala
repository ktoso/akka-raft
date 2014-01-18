package pl.project13.scala.akka.raft

import akka.actor.{Actor, ActorRef, LoggingFSM, FSM}
import scala.concurrent.duration._
import states._
import messages._
import scala.util.{Success, Failure, Random}
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.concurrent.Future
import scala.collection.immutable

trait RaftClient extends LoggingFSM[RaftState, Message[_]] {
  this: Actor =>

  import context.dispatcher

  type RaftLog = ArrayBuffer[RaftLogEntry]
  val Log: RaftLog = new ArrayBuffer[RaftLogEntry]() // todo array backed here?


  val electionTimeout: Duration = randomElectionTimeout(from = 150.millis, to = 300.millis)

  val electingTimeout: Duration = 4.seconds

  var currentTerm = Term.Zero

  var followers = List[FollowerRef]()

  // must be included in leader's heartbeat
  var highestCommittedTermNr: Long = 0

  def isStaleTerm(term: Term): Boolean = term < currentTerm
  def isCurrentOrLaterTerm(term: Term): Boolean = term >= currentTerm
  def nextTerm(): Unit = currentTerm = currentTerm + 1

  // sender aliases, for readability
  @inline def follower = sender()
  @inline def candidate = sender()
  @inline def leader = sender()
  // end of sender aliases

  startWith(Follower, RequestVote.Zero)

  when(Follower) {
    case RequestVote(candidatesLogIndex: Int) if logIndex >= candidatesLogIndex =>
      stay()

    case m: RequestVote =>
      // todo wrong?
      nextTerm()
      goto(Candidate) using RequestVote() forMax electingTimeout

      stay()

    case m: AppendEntries =>
      appendEntriesConsistencyCheck(m)
      stay()
  }

  when(Candidate) {

    case AppendEntries(termNr, _) if isCurrentOrLaterTerm(termNr) =>
      goto(Follower) // todo using

    case AppendEntries(termNr, _) if isStaleTerm(termNr) =>
      log.info(s"Got AppendEntries from stale termNr: $termNr, from: ${sender()}, ignoring.")
      stay()

    // ending election
    case ElectedLeader() =>
      goto(Follower) // todo using???

    case _ if /* gets elected as leader */ false => // todo when is that exactly?
      goto(Leader) using ElectedAsLeader()

    case StateTimeout =>
      ???
  }

  when(Leader) {
    case ElectedAsLeader() =>
      // initialize
      prepareEachFollowersNextIndex(followers, Log)
      stay()

//  todo probably not needed
    case AppendSuccessful =>
      stay()

    case AppendRejected =>
      val followerRef = decrementNextIndexFor(follower)

      // todo continue sending to follower, to make it catch up
      follower ! AppendEntries(currentTerm, Log.slice(followerRef.nextIndex, followerRef.nextIndex + 1).toList)

      stay()

    case x =>
      appendToLog(x) onComplete {
        case Success(_) =>  // todo apply to internal state machine
      }
      stay()
  }

  // helpers

  def logIndex: Int = Log.size - 1

  /** as reaction to a follower not being able to take a write. */
  def decrementNextIndexFor(follower: ActorRef): FollowerRef = {
    val followerRef = followers find { _.ref == follower } getOrElse { throw new RuntimeException(s"Got decrementNextIndex for unknown follower: $follower!")}
    followerRef.decrementNextIndex()
    followerRef
  }

  def appendEntriesConsistencyCheck(appendEntries: AppendEntries) {
    ???
  }

  def prepareEachFollowersNextIndex(followers: immutable.Seq[FollowerRef], Log: RaftLog) {
    val nextIndexForFollowers = logIndex
    followers foreach { _ setNextIndex nextIndexForFollowers }
  }

  def appendToLog(x: Any): Future[Unit] = {
    val nodes: List[ActorRef] = Nil// todo get real list

    // todo send to all, await safe write

    Future()
  }

  def randomElectionTimeout(from: FiniteDuration = 150.millis, to: FiniteDuration = 300.millis): Duration = {
    val fromMs = from.toMillis
    val toMs = to.toMillis
    require(toMs > fromMs, s"to ($to) must be greater than from ($from) in order to create valid election timeout.")

    (fromMs + Random.nextInt(toMs.toInt - fromMs.toInt)).millis
  }

}
