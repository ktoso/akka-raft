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

  def append(term: Term, command: T, client: Option[ActorRef]): ReplicatedLog[T] =
    copy(
      entries = entries :+ Entry(command, term, client)
    )

  // log views
  def entriesFrom(idx: Int, howMany: Int = 5) = entries.slice(idx, idx + howMany)

  def committedEntries = entries.slice(0, commitedIndex)

  def notCommittedEntries = entries.slice(commitedIndex + 1, entries.length)
}

class EmptyReplicatedLog[T <: AnyRef] extends ReplicatedLog[T](Vector.empty, 0, 0) {
  override def lastTerm = Term(0)
  override def lastIndex = -1

  override def commit(n: Int): ReplicatedLog[T] =
    super.commit(n)

  override def append(term: Term, command: T, client: Option[ActorRef]): ReplicatedLog[T] =
    super.append(term, command, client)

}

object ReplicatedLog {
  def empty[T <: AnyRef]: ReplicatedLog[T] = new EmptyReplicatedLog[T]
}

case class Entry[T](
  command: T,
  term: Term,
  client: Option[ActorRef] = None
)