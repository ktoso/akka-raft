package pl.project13.scala.akka.raft

import akka.actor.ActorRef
import scala.collection.immutable

/**
 * '''Mutable''' "member -> number" mapper.

 * Implements convinience methods for maintaining the volatile state on leaders:
 * See: nextIndex[] and matchIndex[] in the Raft paper.
 *
 */
case class LogIndexMap(private var backing: Map[ActorRef, Int]) {

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
    val oldValue = backing(member)

    if (compare(oldValue, value)) {
      put(member, value)
      value
    } else {
      oldValue
    }
  }

  // todo make nicer...
  def indexOnMajority = {
    val index = backing
      .groupBy(_._2)
      .map { case (k, m) => k -> m.size }
      .toList
      .sortBy(- _._2).head // sort by size
      ._1

    index
  }

  
  def valueFor(member: ActorRef): Int = backing(member)
  
}

object LogIndexMap {
  def initialize(members: immutable.Seq[ActorRef], initializeWith: Int) =
    new LogIndexMap(Map(members.map(_ -> initializeWith): _*))
}