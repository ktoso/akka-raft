package pl.project13.scala.akka.raft

import akka.actor.ActorRef

case class ReplicatedLog[T <: AnyRef](
  entries: Vector[Entry[T]],
  commitedIndex: Int,
  lastApplied: Int
) {

  /**
   * Performs the "consistency check", which checks if the data that we just got from the
   *
   * This is O(n) and if the reason why we'll want to compact/snapshot the log.
   */
  def isConsistentWith(otherPrevTerm: Term, otherPrevIndex: Int): Boolean =
    lastTerm == otherPrevTerm && lastIndex == otherPrevIndex

  // log state
  def lastTerm  = entries.lastOption map { _.term } getOrElse Term(0)
  def lastIndex = entries.length - 1

  def prevIndex = lastIndex - 1
  def prevTerm  = if (entries.size < 2) Term(0) else entries.dropRight(1).last.term

  // log actions
  def commit(n: Int): ReplicatedLog[T] =
    copy(commitedIndex = n) // todo persist too, right?

  def append(newEntries: Seq[Entry[T]]): ReplicatedLog[T] =
    copy(entries = entries ++ newEntries)

  def append(term: Term, client: Option[ActorRef], command: T): ReplicatedLog[T] =
    copy(entries = entries :+ Entry(command, term, client))

  // log views

  def apply(idx: Int): Entry[T] = entries(idx)

  /** @param fromExcluding index from which to start the slice (excluding the entry at that index) */
  def entriesBatchFrom(fromExcluding: Int, howMany: Int = 5) = entries.slice(fromExcluding + 1, fromExcluding + 1 + howMany)

  def committedEntries = entries.slice(0, commitedIndex)

  def notCommittedEntries = entries.slice(commitedIndex + 1, entries.length)
}

class EmptyReplicatedLog[T <: AnyRef] extends ReplicatedLog[T](Vector.empty, -1, 0) { // todo lastapplied?
  override def lastTerm = Term(0)
  override def lastIndex = -1
}

object ReplicatedLog {
  def empty[T <: AnyRef]: ReplicatedLog[T] = new EmptyReplicatedLog[T]
}

case class Entry[T](
  command: T,
  term: Term,
  client: Option[ActorRef] = None
)