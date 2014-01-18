package pl.project13.scala.akka.raft

import akka.actor.ActorRef
import scala.collection.immutable

/**
 * Implements convinience methods for maintaining the volatile state on leaders:
 * See: nextIndex[] and matchIndex[] in the Raft paper.
 */
case class LogIndexMap(backing: Map[ActorRef, Int]) {
  def decrementFor(member: ActorRef): LogIndexMap = {
    copy(backing.updated(member, backing(member) - 1))
  }

  def incrementFor(member: ActorRef): LogIndexMap = {
    copy(backing.updated(member, backing(member) - 1))
  }

  def setFor(member: ActorRef, value: Int): LogIndexMap = {
    copy(backing.updated(member, value))
  }

  def valueFor(member: ActorRef): Int = backing(member)
}

object LogIndexMap {
  def initialize(members: immutable.Seq[ActorRef], initializeWith: Int) =
    new LogIndexMap(Map(members.map(_ -> initializeWith): _*))
}