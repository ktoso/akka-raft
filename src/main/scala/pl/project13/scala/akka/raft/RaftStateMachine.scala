package pl.project13.scala.akka.raft

trait RaftStateMachine {

  type Command <: AnyRef

  /** Called when a command is determined by Raft to be safe to apply */
  def apply(command: Command)
}
