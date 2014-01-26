package pl.project13.scala.akka.raft

import akka.actor.{ActorRef, Actor}
import scala.collection.mutable.ListBuffer

class WordConcatRaftStateMachineActor extends Actor with Raft {
  type Command = Cmnd

  var words = ListBuffer[String]()

  var raftListeners: List[ActorRef] = Nil

  /** Called when a command is determined by Raft to be safe to apply */
  def apply(command: Command) = command match {
    case AddListener(actor) =>
      log.info(s"Will also notify ${actor} about on committed message...")
      raftListeners = actor :: raftListeners

    case AppendWord(word, replyTo) =>
      words append word
      log.info(s"Applied command [$command], full words is: $words")

      raftListeners foreach { _ ! word }
      replyTo ! word

    case GetWords(replyTo) =>
      raftListeners foreach { _ ! words.toList }
      replyTo ! words.toList
  }
}

sealed trait Cmnd
case class AppendWord(word: String, replyTo: ActorRef) extends Cmnd
case class GetWords(replyTo: ActorRef) extends Cmnd
case class AddListener(replyTo: ActorRef) extends Cmnd
