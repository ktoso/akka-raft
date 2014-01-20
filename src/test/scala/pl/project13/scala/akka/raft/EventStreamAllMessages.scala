package pl.project13.scala.akka.raft

import akka.actor.Actor

/**
 * Forwards all messages received to the system's EventStream.
 * Use this to deterministically `awaitForLeaderElection` etc.
 */
trait EventStreamAllMessages {
  this: Actor =>

  override def aroundReceive(receive: Actor.Receive, msg: Any) = {
    context.system.eventStream.publish(msg.asInstanceOf[AnyRef])

    receive.applyOrElse(msg, unhandled)
  }
}
