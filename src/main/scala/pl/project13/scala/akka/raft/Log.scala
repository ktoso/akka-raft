package pl.project13.scala.akka.raft

import akka.actor.ActorRef
import scala.collection.immutable

case class Entry[T <: AnyRef](
  command: T,
  term: Term,
  client: Option[ActorRef] = None
)

case class Log[T <: AnyRef](
  entries: List[Entry[T]],
  commitedIndex: Int,
  lastApplied: Int
) {
  def lastIndex: Int = entries.length - 1
  def commit(n: Int) = copy(commitedIndex = n)

  // todo inverse, because we prepend, not append
  def entriesFrom(idx: Int, howMany: Int = 5) = entries.slice(idx, idx + howMany)
  def notCommittedEntries = entries.slice(commitedIndex + 1, entries.length)

  def append(term: Term, command: T, client: Option[ActorRef]): Log[T] =
    copy(entries = Entry(command, term, client) :: entries)
}

object Log {
  def empty[T <: AnyRef] = Log[T](Nil, 0, 0)
}
