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
    (otherPrevTerm == Term(0) && otherPrevIndex == 0) || (lastTerm == otherPrevTerm && lastIndex == otherPrevIndex)

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
  def nextIndex = entries.size

  // log actions
  def commit(n: Int): ReplicatedLog[Command] =
    copy(committedIndex = n)

  def append(entry: Entry[Command], take: Int = entries.length): ReplicatedLog[Command] =
    append(List(entry), take)

  def append(entriesToAppend: Seq[Entry[Command]], take: Int): ReplicatedLog[Command] =
    copy(entries = entries.take(take) ++ entriesToAppend)

  def +(newEntry: Entry[Command]): ReplicatedLog[Command] =
    append(List(newEntry), entries.size)

  def putWithDroppingInconsistent(replicatedEntry: Entry[Command]): ReplicatedLog[Command] = {
    val replicatedIndex = replicatedEntry.index
    if (entries.isDefinedAt(replicatedIndex)) { // must now use index since we can have snapshots
      val localEntry = entries(replicatedIndex)

      if (localEntry == replicatedEntry) this // we're consistent with the replicated log
      else copy(entries = entries.slice(0, replicatedIndex) :+ replicatedEntry) // dropping everything until the entry that does not match
    } else {
      // nothing to drop
      this
    }
  }

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

  def apply(idx: Int): Entry[Command] = entries(idx)

  /** @param fromIncluding index from which to start the slice (excluding the entry at that index) */
  def entriesBatchFrom(fromIncluding: Int, howMany: Int = 5): List[Entry[Command]] = {
    val toSend = entries.slice(fromIncluding, fromIncluding + howMany)
    toSend.headOption match {
      case Some(head) =>
        val batchTerm = head.term
        toSend.takeWhile(_.term == batchTerm) // we only batch commands grouped by their term

      case None =>
        List.empty
    }
  }

  def between(fromIndex: Int, toIndex: Int): List[Entry[Command]] =
    entries.slice(fromIndex + 1, toIndex + 1)

  def firstIndexInTerm(term: Term): Int = term.termNr match {
    case 0 => 0
    case 1 => 0
    case _ => entries.zipWithIndex find { case (e, i) => e.term == term } map { _._2 } getOrElse 0
  }

  def termAt(index: Int) =
    if (index <= 0) Term(0)
    else entries.find(_.index == index).getOrElse(throw new RuntimeException(s"Unable to find log entry at index $index")).term

  def committedEntries = entries.slice(0, committedIndex)

  def notCommittedEntries = entries.slice(committedIndex + 1, entries.length)
}

class EmptyReplicatedLog[T](defaultBatchSize: Int) extends ReplicatedLog[T](List.empty, -1, defaultBatchSize) {
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

  def isSnapshot = false

  def prevTerm = term.prev

  def prevIndex = index - 1 match {
    case -1 => 0
    case n  => n
  }
}

trait SnapshotEntry {
  this: Entry[_] =>
  def data: Any = command

  override def isSnapshot = true
  override def toString = "Snapshot" + super.toString
}