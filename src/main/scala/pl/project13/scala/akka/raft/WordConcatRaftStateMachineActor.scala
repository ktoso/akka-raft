package pl.project13.scala.akka.raft

import akka.actor.{ActorRef, Actor}
import scala.collection.mutable.ListBuffer

class WordConcatRaftStateMachineActor extends Actor with Raft {
  type Command = Cmnd

  var words = ListBuffer[String]()

  var raftListeners: List[ActorRef] = Nil

  /** Called when a command is determined by Raft to be safe to apply */
  def apply(command: Command): Any = command match {
    case AddListener(listener) =>
      log.info(s"Will also notify $listener about on committed message...")
      raftListeners = listener :: raftListeners

    case AppendWord(word) =>
      words append word
      log.info(s"Applied command [$command], full words is: $words")

      raftListeners foreach { _ ! word }
      word

    case GetWords() =>
      raftListeners foreach { _ ! words.toList }
      words.toList
  }
}

sealed trait Cmnd
case class AppendWord(word: String)       extends Cmnd
case class GetWords()                     extends Cmnd
case class AddListener(replyTo: ActorRef) extends Cmnd
