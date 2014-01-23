package pl.project13.scala.akka.raft

import akka.actor.{ActorRef, Actor}
import scala.collection.immutable.Seq
import scala.collection.mutable.ListBuffer

class RaftActor extends Actor with Raft {
  type Command = Cmnd

  var words = ListBuffer[String]()
  
  /** Called when a command is determined by Raft to be safe to apply */
  def apply(command: Command) = command match {
    case AppendWord(word, replyTo) =>
      words append word
      log.info(s"Applied command [$command], full words is: $words")
      replyTo ! word

    case GetWords(replyTo) =>
      replyTo ! words.toList
  }
}

sealed trait Cmnd
case class AppendWord(word: String, replyTo: ActorRef) extends Cmnd
case class GetWords(replyTo: ActorRef) extends Cmnd
