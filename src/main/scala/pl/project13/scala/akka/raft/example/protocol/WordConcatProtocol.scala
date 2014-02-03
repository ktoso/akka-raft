package pl.project13.scala.akka.raft.example.protocol

trait WordConcatProtocol {
  sealed trait Cmnd
  case class AppendWord(word: String) extends Cmnd
  case object GetWords                extends Cmnd
}
