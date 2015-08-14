package pl.project13.scala.akka.raft.model

import akka.actor.ActorRef
import scala.collection.immutable
import scala.annotation.tailrec
import pl.project13.scala.akka.raft.{StableClusterConfiguration, JointConsensusClusterConfiguration, ClusterConfiguration}

/**
 * '''Mutable''' "member -> number" mapper.

 * Implements convinience methods for maintaining the volatile state on leaders:
 * See: nextIndex[] and matchIndex[] in the Raft paper.
 *
 */
case class LogIndexMap private (private var backing: Map[ActorRef, Int], private val initializeWith: Int) {

  def decrementFor(member: ActorRef): Int = {
    val value = backing(member) - 1
    backing = backing.updated(member, value)
    value
  }

  def incrementFor(member: ActorRef): Int = {
    val value = backing(member) + 1
    backing = backing.updated(member, value)
    value
  }

  def put(member: ActorRef, value: Int) = {
    backing = backing.updated(member, value)
  }

  /** Only put the new `value` if it is __greater than__ the already present value in the map */
  def putIfGreater(member: ActorRef, value: Int): Int =
    putIf(member, _ < _, value)

  /** Only put the new `value` if it is __smaller than__ the already present value in the map */
  def putIfSmaller(member: ActorRef, value: Int): Int =
    putIf(member, _ > _, value)

  /** @param compare (old, new) => should put? */
  def putIf(member: ActorRef, compare: (Int, Int) => Boolean, value: Int): Int = {
    val oldValue = valueFor(member)

    if (compare(oldValue, value)) {
      put(member, value)
      value
    } else {
      oldValue
    }
  }

  def consensusForIndex(config: ClusterConfiguration): Int = config match {
    case StableClusterConfiguration(_, members) =>
      indexOnMajority(members)

    case JointConsensusClusterConfiguration(_, oldMembers, newMembers) =>
      // during joined consensus, in order to commit a value, consensus must be achieved on BOTH member sets.
      // this guarantees safety once we switch to the new configuration, and oldMembers go away. More details in §6.
      val oldQuorum = indexOnMajority(oldMembers)
      val newQuorum = indexOnMajority(newMembers)

      if (oldQuorum == 0) newQuorum
      else if (newQuorum == 0) oldQuorum
      else math.min(oldQuorum, newQuorum)
  }

  private def indexOnMajority(include: Set[ActorRef]): Int = {
    // Our goal is to find the match index e that has the
    // following property:
    //   - a quorum [N / 2 + 1] of the nodes has match index >= e

    // We first sort the match indices
    val sortedMatchIndices = backing
      .filterKeys(include)
      .values
      .toList
      .sorted

    if (sortedMatchIndices.isEmpty) {
      return 0
    }

    assert(sortedMatchIndices.size == include.size)

    // The element e we are looking is now at the (CEILING[N / 2] - 1)st index.
    // [ consider three examples:
    //   1,1,1,3,3  => correct value: 1
    //   1,1,3,3,3  => correct value: 3
    //   1,1,3,3    => correct value: 1
    // ]
    return sortedMatchIndices(LogIndexMap.ceiling(include.size, 2) - 1)
  }

  @tailrec final def valueFor(member: ActorRef): Int = backing.get(member) match {
    case None =>
      backing = backing.updated(member, initializeWith)
      valueFor(member)
    case Some(value) =>
      value
  }
}

object LogIndexMap {
  def initialize(members: Set[ActorRef], initializeWith: Int) =
    new LogIndexMap(Map(members.toList.map(_ -> initializeWith): _*), initializeWith)

  def ceiling(numerator: Int, divisor: Int): Int = {
    if (numerator % divisor == 0) numerator / divisor else (numerator / divisor) + 1
  }
}
