package pl.project13.scala.akka.raft.protocol

import akka.actor.ActorRef
import pl.project13.scala.akka.raft.ClusterConfiguration
import pl.project13.scala.akka.raft.model.Term

/**
 * Events used to persist changes to FSM internal state
 */
sealed trait DomainEvent
case class UpdateTermEvent(t: Term) extends DomainEvent
case class VoteForEvent(voteFor: ActorRef) extends DomainEvent
case class VoteForSelfEvent() extends DomainEvent
case class IncrementVoteEvent() extends DomainEvent
case class GoToFollowerEvent(t: Option[Term] = None) extends DomainEvent
case class WithNewClusterSelf(self: ActorRef) extends DomainEvent
case class WithNewConfigEvent(t: Option[Term] = None, config: ClusterConfiguration) extends DomainEvent
case class GoToLeaderEvent() extends DomainEvent
case class StartElectionEvent() extends DomainEvent
case class KeepStateEvent() extends DomainEvent

