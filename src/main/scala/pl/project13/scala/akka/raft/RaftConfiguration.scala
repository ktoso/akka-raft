package pl.project13.scala.akka.raft

import akka.actor.ActorRef

sealed trait RaftConfiguration {
  def members: Set[ActorRef]

  def isTransitioning: Boolean

  def transitionTo(newConfiguration: RaftConfiguration): RaftConfiguration

  /**
   * Basically "drop" ''old configuration'' and keep using only the new one.
   *
   * {{{
   *   StableConfiguration                   => StableConfiguration
   *   JointConsensusConfuguration(old, new) => StableConfiguration(new)
   * }}}
   */
  def transitionToStable: RaftConfiguration

  /** When in the middle of a configuration migration we may need to know if we're part of the new config (in order to step down if not) */
  def isPartOfNewConfiguration(member: ActorRef): Boolean
}

object RaftConfiguration {
  def apply(members: Iterable[ActorRef]): RaftConfiguration =
    StableRaftConfiguration(members.toSet)

  def apply(members: ActorRef*): RaftConfiguration =
    StableRaftConfiguration(members.toSet)
}

case class StableRaftConfiguration(members: Set[ActorRef]) extends RaftConfiguration {
  val isTransitioning = false

  def transitionTo(newConfiguration: RaftConfiguration) =
    JointConsensusRaftConfiguration(members, newConfiguration.members)


  def isPartOfNewConfiguration(ref: ActorRef) = true

  def transitionToStable = this

  override def toString = s"StableRaftConfiguration(${members.map(_.path.elements.last)})"
}

/**
 * Configuration during transition to new configuration consists of both old / new member sets.
 * As the configuration is applied, the old configuration may be discarded.
 *
 * During the transition phase:
 *
 *  - Log entries are replicated to all members in both configurations
 *  - Any member from either configuration may serve as Leader
 *  - Agreement (for elections and entry commitment) requires majoritis from ''both'' old and new configurations
 *
 * @todo make it possible to enqueue multiple configuration changes and apply them in order
 */
case class JointConsensusRaftConfiguration(oldMembers: Set[ActorRef], newMembers: Set[ActorRef]) extends RaftConfiguration {

  /** Members from both configurations participate in the joint consensus phase */
  val members = oldMembers union newMembers

  val isTransitioning = true

  // todo maybe handle this with enqueueing this config change?
  def transitionTo(newConfiguration: RaftConfiguration) =
    throw new IllegalStateException(s"Cannot start another configuration transition, already in progress! " +
      s"Migrating from [${oldMembers.size}] $oldMembers to [${newMembers.size}] $newMembers")

  /** When in the middle of a configuration migration we may need to know if we're part of the new config (in order to step down if not) */
  def isPartOfNewConfiguration(member: ActorRef): Boolean = newMembers contains member

  def transitionToStable = StableRaftConfiguration(newMembers)

  override def toString =
    s"JointConsensusRaftConfiguration(old:${oldMembers.map(_.path.elements.last)}, new:${newMembers.map(_.path.elements.last)}})"
}
