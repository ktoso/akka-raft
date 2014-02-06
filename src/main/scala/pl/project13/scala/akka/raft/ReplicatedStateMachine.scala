package pl.project13.scala.akka.raft

trait ReplicatedStateMachine {

  type ReplicatedStateMachineApply = PartialFunction[Any, Any]
  
  /**
   * Use this method to change the actor's internal state.
   * It will be called with whenever a message is committed by the raft cluster.
   *
   * Please note that this is different than a plain `receive`, because the returned value from application
   * will be sent back _by the leader_ to the client that originaly has sent the message.
   *
   * All other __Followers__ will also apply this message to their internal state machines when it's committed,
   * although the result of those applications will ''not'' be sent back to the client who originally sent the message -
   * only the __Leader__ responds to the client (`1 message <-> 1 response`). Although you're free to use `!` inside an
   * apply (resulting in possibly `1 message <-> n messages`).
   *
   * You can treat this as a raft equivalent of receive, with the difference that apply is guaranteed to be called,
   * only after the message has been propagated to the majority of members.
   */
  def apply: ReplicatedStateMachineApply
  
}
