package pl.project13.scala.akka.raft.example

import protocol._

import scala.collection.mutable.ListBuffer
import pl.project13.scala.akka.raft.RaftActor

class WordConcatRaftActor extends RaftActor {

  type Command = Cmnd

  var words = Vector[String]()

  /** Called when a command is determined by Raft to be safe to apply */
  def apply = {
    case AppendWord(word) =>
      words = words :+ word
      log.info(s"Applied command [AppendWord($word)], full words is: $words")

      word

    case GetWords =>
      log.info("Replying with {}", words.toList)
      words.toList
  }
}

