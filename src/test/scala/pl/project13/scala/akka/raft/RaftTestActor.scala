package pl.project13.scala.akka.raft

import akka.actor.Actor
import scala.collection.mutable.ArrayBuffer

class RaftTestActor extends Actor with Raft {
  type Command = String

  val words = ArrayBuffer[String]()
  
  def apply(word: Command): Unit = {
    words += word
  }
}
