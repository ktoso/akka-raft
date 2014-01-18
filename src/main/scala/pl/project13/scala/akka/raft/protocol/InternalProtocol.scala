package pl.project13.scala.akka.raft.protocol

import akka.actor.ActorRef
import scala.collection.immutable

trait InternalProtocol {
  sealed trait InternalMessage  extends Message[Internal]
  sealed trait FollowerResponse extends Message[Internal]

  case class ElectedAsLeader() extends InternalMessage
  case class ElectedLeader()   extends InternalMessage

  /** When the Leader has sent an append, for an unexpected number, the Follower replies with this */
  case object AppendRejected   extends FollowerResponse
  case object AppendSuccessful extends FollowerResponse
}
