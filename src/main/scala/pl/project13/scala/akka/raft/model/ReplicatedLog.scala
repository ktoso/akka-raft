package pl.project13.scala.akka.raft.model

import akka.actor.ActorRef
import scala.annotation.switch

/**
 * @param defaultBatchSize number of commands that can be sent together in one [[pl.project13.scala.akka.raft.protocol.RaftProtocol.AppendEntries]] message.
 */
case class ReplicatedLog[Command](
  private[raft] val entries: List[Entry[Command]],
  committedIndex: Int,
  defaultBatchSize: Int) {

  def length = entries.length

  def commands: List[Command] = entries.map(_.command)

  /**
   * Performs the "consistency check", which checks if the data that we just got from the
   */
  def containsMatchingEntry(otherPrevTerm: Term, otherPrevIndex: Int): Boolean =
    (otherPrevTerm == Term(0) && otherPrevIndex == 0) ||
    (entries.isDefinedAt(otherPrevIndex - 1) && entries(otherPrevIndex - 1).term == otherPrevTerm)

  // log state
  def lastTerm  = entries.lastOption map { _.term } getOrElse Term(0)
  def lastIndex = entries.lastOption map { _.index } getOrElse 0

  def prevIndex = (lastIndex: @switch) match {
    case 0 => 0 // special handling of initial case, we don't go into negative indexes
    case n => n - 1
  }
  def prevTerm  = if (entries.size < 2) Term(0) else entries.dropRight(1).last.term

  /**
   * Determines index of the next Entry that will be inserted into this log.
   * Handles edge cases, use this instead of +1'ing manualy.
   */
  def nextIndex =
    // First entry gets index 1 (not 0, which indicates empty log)
    entries.size + 1

  // log actions
  def commit(n: Int): ReplicatedLog[Command] =
    copy(committedIndex = n)

  def append(entry: Entry[Command], take: Int = entries.length): ReplicatedLog[Command] =
    append(List(entry), take)

  def append(entriesToAppend: Seq[Entry[Command]], take: Int): ReplicatedLog[Command] =
    copy(entries = entries.take(take) ++ entriesToAppend)

  def +(newEntry: Entry[Command]): ReplicatedLog[Command] =
    append(List(newEntry), entries.size)

  def compactedWith(snapshot: RaftSnapshot): ReplicatedLog[Command] = {
    val snapshotHasLaterTerm = snapshot.meta.lastIncludedTerm > lastTerm
    val snapshotHasLaterIndex = snapshot.meta.lastIncludedIndex > lastIndex

    if (snapshotHasLaterTerm && snapshotHasLaterIndex) {
      // total substitution, snapshot is from the future = replace all we had
      copy(entries = snapshot.toEntrySingleList)
    } else {
      val entriesWhereSnapshotteEntriesDropped = entries dropWhile { e => e.index <= snapshot.meta.lastIncludedIndex }
      copy(entries = snapshot.toEntry[Command] :: entriesWhereSnapshotteEntriesDropped)
    }
  }

  // log views

  /**
   * @param fromIncluding index from which to start the slice (including the entry at that index)
   *
   * N.B. log entries are 1-indexed.
   */
  def entriesBatchFrom(fromIncluding: Int, howMany: Int = 5): List[Entry[Command]] = {
    val adjusted = fromIncluding - 1
    assert(adjusted >= 0)
    val toSend = entries.slice(adjusted, adjusted + howMany)
    toSend.headOption match {
      case Some(head) =>
        val batchTerm = head.term
        toSend.takeWhile(_.term == batchTerm) // we only batch commands grouped by their term

      case None =>
        List.empty
    }
  }

  // fromIndex is exclusive
  // toIndex is inclusive
  // N.B. given indices should be 1-indexed
  def between(fromIndex: Int, toIndex: Int): List[Entry[Command]] =
    // adjusted of fromIndex: fromIndex - 1. So, fromIndex is exclusive.
    entries.slice(fromIndex, toIndex)

  def containsEntryAt(index: Int) =
    !entries.find(_.index == index).isEmpty

  // Throws IllegalArgumentException if there is no entry with the given index
  def termAt(index: Int): Term = {
    if (index <= 0) return Term(0)
    if (!containsEntryAt(index)) {
      throw new IllegalArgumentException(s"Unable to find log entry at index $index.")
    }
    return entries.find(_.index == index).get.term
  }

  def committedEntries = entries.slice(0, committedIndex)

  def notCommittedEntries = entries.slice(committedIndex + 1, entries.length)
}

class EmptyReplicatedLog[T](defaultBatchSize: Int) extends ReplicatedLog[T](List.empty, 0, defaultBatchSize) {
  override def lastTerm = Term(0)
  override def lastIndex = 0
}

object ReplicatedLog {
  def empty[T](defaultBatchSize: Int): ReplicatedLog[T] = new EmptyReplicatedLog[T](defaultBatchSize)
}

case class Entry[T](
  command: T,
  term: Term,
  index: Int,
  client: Option[ActorRef] = None
) {
  assert(index > 0)

  def isSnapshot = false

  def prevTerm = term.prev

  def prevIndex = index - 1
}

trait SnapshotEntry {
  this: Entry[_] =>
  def data: Any = command

  override def isSnapshot = true
  override def toString = "Snapshot" + super.toString
}
