package pl.project13.scala.akka.raft.protocol

/**
 * States used by the Raft FSM.
 *
 * Use by importing the protocol package:
 * {{{import akka.raft.protocol._}}}
 */
trait RaftStates {

  sealed trait RaftState

  /** In this phase the member awaits to get it's [[pl.project13.scala.akka.raft.ClusterConfiguration]] */
  case object Init      extends RaftState

  /** A Follower can take writes from a Leader; If doesn't get any heartbeat, may decide to become a Candidate */
  case object Follower  extends RaftState

  /** A Candidate tries to become a Leader, by issuing [[pl.project13.scala.akka.raft.protocol.RaftProtocol.RequestVote]] */
  case object Candidate extends RaftState

  /** The Leader is responsible for taking writes, and commiting entries, as well as keeping the heartbeat to all members */
  case object Leader    extends RaftState
}
