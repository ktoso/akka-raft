package pl.project13.scala.akka.raft

import akka.actor.ActorRef

sealed trait ClusterConfiguration {
  def members: Set[ActorRef]

  def sequenceNumber: Long

  def isOlderThan(that: ClusterConfiguration) = this.sequenceNumber <= that.sequenceNumber

  def isNewerThan(that: ClusterConfiguration) = this.sequenceNumber > that.sequenceNumber

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
    StableClusterConfiguration(0, members.toSet)

  def apply(members: ActorRef*): ClusterConfiguration =
    StableClusterConfiguration(0, members.toSet)
}

/**
 * Used for times when the cluster is NOT undergoing membership changes.
 * Use `transitionTo` in order to enter a [[pl.project13.scala.akka.raft.JointConsensusClusterConfiguration]] state.
 */
case class StableClusterConfiguration(sequenceNumber: Long, members: Set[ActorRef]) extends ClusterConfiguration {
  val isTransitioning = false

  /**
   * Implementation detail: The resulting configurations `sequenceNumber` will be equal to the current one.
   */
  def transitionTo(newConfiguration: ClusterConfiguration): JointConsensusClusterConfiguration =
    JointConsensusClusterConfiguration(sequenceNumber, members, newConfiguration.members)

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
 */
case class JointConsensusClusterConfiguration(sequenceNumber: Long, oldMembers: Set[ActorRef], newMembers: Set[ActorRef]) extends ClusterConfiguration {

  /** Members from both configurations participate in the joint consensus phase */
  val members = oldMembers union newMembers

  val isTransitioning = true

  /**
   * Implementation detail: The resulting stable configurations `sequenceNumber` will be incremented from the current one, to mark the following "stable phase".
   */
  def transitionTo(newConfiguration: ClusterConfiguration) =
    throw new IllegalStateException(s"Cannot start another configuration transition, already in progress! " +
      s"Migrating from [${oldMembers.size}] $oldMembers to [${newMembers.size}] $newMembers")

  /** When in the middle of a configuration migration we may need to know if we're part of the new config (in order to step down if not) */
  def isPartOfNewConfiguration(member: ActorRef): Boolean = newMembers contains member

  def transitionToStable = StableClusterConfiguration(sequenceNumber + 1, newMembers)

  override def toString =
    s"JointConsensusRaftConfiguration(old:${oldMembers.map(_.path.elements.last)}, new:${newMembers.map(_.path.elements.last)})"
}
