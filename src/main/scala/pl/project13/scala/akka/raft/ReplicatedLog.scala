package pl.project13.scala.akka.raft

import akka.actor.ActorRef
import scala.annotation.switch

case class ReplicatedLog[Command <: AnyRef](
  entries: Vector[Entry[Command]],
  commitedIndex: Int
) {

  def commands = entries.map(_.command)

  /**
   * Performs the "consistency check", which checks if the data that we just got from the
   */
  def containsMatchingEntry(otherPrevTerm: Term, otherPrevIndex: Int): Boolean =
    (otherPrevIndex == 0 && entries.isEmpty) || (lastTerm == otherPrevTerm && lastIndex == otherPrevIndex)

  // log state
  def lastTerm  = entries.lastOption map { _.term } getOrElse Term(0)
  def lastIndex = entries.length - 1

  def prevIndex = (lastIndex: @switch) match {
    case 0 => 0 // special handling of initial case, we don't go into negative indexes
    case n => n -1
  }
  def prevTerm  = if (entries.size < 2) Term(0) else entries.dropRight(1).last.term

  /**
   * Determines index of the next Entry that will be inserted into this log.
   * Handles edge cases, use this instead of +1'ing manualy.
   */
  def nextIndex = entries.size

  // log actions
  def commit(n: Int): ReplicatedLog[Command] =
    copy(commitedIndex = n)

  def append(newEntry: Entry[Command]): ReplicatedLog[Command] = {
    val allEntries = entries :+ newEntry.copy(index = nextIndex) // todo make it come in with the right index rigth away
    require(allEntries.map(_.index).size == allEntries.map(_.index).toSet.size, "Must not allow duplicates in index!     " + entries + "    " + newEntry)

    copy(entries = allEntries)
  }

  def +(newEntry: Entry[Command]): ReplicatedLog[Command] =
    append(newEntry)

  def putWithDroppingInconsistent(replicatedEntry: Entry[Command]): ReplicatedLog[Command] = {
    val replicatedIndex = replicatedEntry.index
    if (entries.isDefinedAt(replicatedIndex)) {
      val localEntry = entries(replicatedIndex)

      if (localEntry == replicatedEntry)
        this // we're consistent with the replicated log
      else
        copy(entries = entries.slice(0, replicatedIndex) :+ replicatedEntry) // dropping everything until the entry that does not match
    } else {
      // nothing to drop
      this
    }
  }

  // log views

  def apply(idx: Int): Entry[Command] = entries(idx)

  /** @param fromIncluding index from which to start the slice (excluding the entry at that index) */
  def entriesBatchFrom(fromIncluding: Int, howMany: Int = 5): Vector[Entry[Command]] =
    entries.slice(fromIncluding, fromIncluding + howMany)
  
  def commandsBatchFrom(fromIncluding: Int, howMany: Int = 5): Vector[Command] =
      entriesBatchFrom(fromIncluding, howMany).map(_.command)

  def firstIndexInTerm(term: Term): Int = term.termNr match {
    case 0 => 0
    case 1 => 0
    case _ => entries.zipWithIndex find { case (e, i) => e.term == term } map { _._2 } getOrElse 0
  }

  def termAt(index: Int) =
    if (index < 0) Term(0)
    else entries(index).term

  def committedEntries = entries.slice(0, commitedIndex)

  def notCommittedEntries = entries.slice(commitedIndex + 1, entries.length)
}

class EmptyReplicatedLog[T <: AnyRef] extends ReplicatedLog[T](Vector.empty, -1) {
  override def lastTerm = Term(0)
  override def lastIndex = 0
}

object ReplicatedLog {
  def empty[T <: AnyRef]: ReplicatedLog[T] = new EmptyReplicatedLog[T]
}

case class Entry[T](
  command: T,
  term: Term,
  index: Int,
  client: Option[ActorRef] = None
)