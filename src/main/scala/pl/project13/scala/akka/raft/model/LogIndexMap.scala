package pl.project13.scala.akka.raft.model

import akka.actor.ActorRef
import scala.collection.immutable
import scala.annotation.tailrec

/**
 * '''Mutable''' "member -> number" mapper.

 * Implements convinience methods for maintaining the volatile state on leaders:
 * See: nextIndex[] and matchIndex[] in the Raft paper.
 *
 */
case class LogIndexMap private (private var backing: Map[ActorRef, Int], private val initializeWith: Int) {

  def decrementFor(member: ActorRef): Int = backing(member) match {
    case 0 => 0
    case n =>
      val value = n - 1
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

  // todo make nicer...
  def indexOnMajority = {
    backing
      .groupBy(_._2)
      .map { case (k, m) => k -> m.size }
      .toList
      .sortBy(- _._2).head // sort by size
      ._1
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
}
