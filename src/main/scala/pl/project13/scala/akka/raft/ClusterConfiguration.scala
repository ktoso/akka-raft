package pl.project13.scala.akka.raft

import akka.actor.ActorRef

sealed trait ClusterConfiguration {
  def members: Set[ActorRef]

  def isTransitioning: Boolean

  def transitionTo(newConfiguration: ClusterConfiguration): ClusterConfiguration

  /**
   * Basically "drop" ''old configuration'' and keep using only the new one.
   *
   * {{{
   *   StableConfiguration                   => StableConfiguration
   *   JointConsensusConfuguration(old, new) => StableConfiguration(new)
   * }}}
   */
  def transitionToStable: ClusterConfiguration

  /** When in the middle of a configuration migration we may need to know if we're part of the new config (in order to step down if not) */
  def isPartOfNewConfiguration(member: ActorRef): Boolean
}

object ClusterConfiguration {
  def apply(members: Iterable[ActorRef]): ClusterConfiguration =
    StableClusterConfiguration(members.toSet)

  def apply(members: ActorRef*): ClusterConfiguration =
    StableClusterConfiguration(members.toSet)
}

/**
 * Used for times when the cluster is NOT undergoing membership changes.
 * Use `transitionTo` in order to enter a [[pl.project13.scala.akka.raft.JointConsensusClusterConfiguration]] state.
 */
case class StableClusterConfiguration(members: Set[ActorRef]) extends ClusterConfiguration {
  val isTransitioning = false

  def transitionTo(newConfiguration: ClusterConfiguration) =
    JointConsensusClusterConfiguration(members, newConfiguration.members)

  def isPartOfNewConfiguration(ref: ActorRef) = members contains ref

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
case class JointConsensusClusterConfiguration(oldMembers: Set[ActorRef], newMembers: Set[ActorRef]) extends ClusterConfiguration {

  /** Members from both configurations participate in the joint consensus phase */
  val members = oldMembers union newMembers

  val isTransitioning = true

  // todo maybe handle this with enqueueing this config change?
  def transitionTo(newConfiguration: ClusterConfiguration) =
    throw new IllegalStateException(s"Cannot start another configuration transition, already in progress! " +
      s"Migrating from [${oldMembers.size}] $oldMembers to [${newMembers.size}] $newMembers")

  /** When in the middle of a configuration migration we may need to know if we're part of the new config (in order to step down if not) */
  def isPartOfNewConfiguration(member: ActorRef): Boolean = newMembers contains member

  def transitionToStable = StableClusterConfiguration(newMembers)

  override def toString =
    s"JointConsensusRaftConfiguration(old:${oldMembers.map(_.path.elements.last)}, new:${newMembers.map(_.path.elements.last)}})"
}
