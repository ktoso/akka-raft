package pl.project13.scala.akka.raft.protocol

/**
 * States used by the Raft FSM
 */
trait RaftStates {

  sealed trait RaftState
  case object Follower  extends RaftState
  case object Candidate extends RaftState
  case object Leader    extends RaftState
}
