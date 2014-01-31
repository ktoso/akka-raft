package pl.project13.scala.akka.raft

import akka.actor.{ActorRef, Actor}
import scala.collection.mutable.ListBuffer

class WordConcatRaftStateMachineActor extends RaftActor {

  type Command = Cmnd

  var words = ListBuffer[String]()

  /** Called when a command is determined by Raft to be safe to apply */
  def apply(command: Cmnd): Any = command match {
    case AppendWord(word) =>
      words append word
      log.info(s"Applied command [$command], full words is: $words")

      word

    case GetWords() =>
      words.toList
  }
}

sealed trait Cmnd
case class AppendWord(word: String)       extends Cmnd
case class GetWords()                     extends Cmnd // todo make object
