package pl.project13.scala.akka.raft.example

sealed trait Cmnd
case class AppendWord(word: String) extends Cmnd
case object GetWords                extends Cmnd // todo make object

