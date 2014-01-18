package pl.project13.scala.akka.raft

import akka.actor.{ActorRef, Actor}
import scala.collection.immutable.Seq
import scala.collection.mutable.ListBuffer

class RaftActor extends Actor with Raft {
  type Command = String

  var words = ListBuffer[String]()
  
  /** Called when a command is determined by Raft to be safe to apply */
  def apply(command: Command) = command match {
    case word =>
      words append word
      log.info(s"Applied command [$command], full words is: $words")
  }
}
