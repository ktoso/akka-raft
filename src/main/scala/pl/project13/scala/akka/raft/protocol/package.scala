package pl.project13.scala.akka.raft

package object protocol
  extends RaftStates
  with RaftProtocol
  with InternalProtocol
  with StateMetadata
