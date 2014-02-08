package pl.project13.scala.akka.raft

import akka.actor.{Actor, ActorLogging}

/**
 * TODO
 * https://github.com/ktoso/akka-raft/issues/13
 * https://github.com/ktoso/akka-raft/issues/15
 *
 * Note to self, I think a proxy one will be easier to implement, because we can delay sending msgs, until we get info
 * that a leader was selected, and it's easier to retry sending hm hm... We'll see.
 */
class RaftClientActor extends Actor with ActorLogging {

  ???

  def receive: Actor.Receive = ???
}
