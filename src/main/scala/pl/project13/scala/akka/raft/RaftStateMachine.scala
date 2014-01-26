package pl.project13.scala.akka.raft

trait RaftStateMachine {

  type Command <: AnyRef

  /**
   * Called when a command is determined by Raft to be safe to apply (comitted on majority of members).
   *
   * The result of this apply will be sent back to the original client, issuing the command to the Raft State Machine.
   */
  def apply(command: Command): Any
}
