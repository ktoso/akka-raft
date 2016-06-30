package pl.project13.scala.akka.raft.protocol

import akka.persistence.fsm.PersistentFSM.FSMState

/**
 * States used by the Raft FSM.
 *
 * Use by importing the protocol package:
 * {{{import akka.raft.protocol._}}}
 */
trait RaftStates {

  sealed trait RaftState extends FSMState

  /** In this phase the member awaits to get it's [[pl.project13.scala.akka.raft.ClusterConfiguration]] */
  case object Init extends RaftState {
    override def identifier: String = "Init"
  }

  /** A Follower can take writes from a Leader; If doesn't get any heartbeat, may decide to become a Candidate */
  case object Follower extends RaftState {
    override def identifier: String = "Follower"
  }

  /** A Candidate tries to become a Leader, by issuing [[pl.project13.scala.akka.raft.protocol.RaftProtocol.RequestVote]] */
  case object Candidate extends RaftState {
    override def identifier: String = "Candidate"
  }

  /** The Leader is responsible for taking writes, and committing entries, as well as keeping the heartbeat to all members */
  case object Leader extends RaftState {
    override def identifier: String = "Leader"
  }
}
