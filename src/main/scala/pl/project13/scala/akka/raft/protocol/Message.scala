package pl.project13.scala.akka.raft.protocol

/** Message with phantom type, used to differenciate between Internal and Raft messages */
class Message[T <: MessageType]

sealed trait MessageType
              class Raft            extends MessageType
private[raft] class Internal        extends MessageType
private[raft] class InternalCluster extends MessageType
private[raft] class Testing         extends Internal
