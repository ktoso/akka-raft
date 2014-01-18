package pl.project13.scala.akka.raft.protocol

/** Message with phantom type, used to differenciate between Internal and Raft messages */
class Message[T <: MessageType]

sealed trait MessageType
final class Internal extends MessageType
final class Raft     extends MessageType
