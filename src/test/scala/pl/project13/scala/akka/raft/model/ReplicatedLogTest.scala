package pl.project13.scala.akka.raft.model

import org.scalatest._
import pl.project13.scala.akka.raft.model._
import pl.project13.scala.akka.raft.example.protocol.WordConcatProtocol
import akka.persistence.serialization.Snapshot
import pl.project13.scala.akka.raft.ClusterConfiguration

class ReplicatedLogTest extends FlatSpec with Matchers
  with WordConcatProtocol {

  behavior of "ReplicatedLog"

  it should "should contain commands and terms when they were recieved by leader" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String](1)

    val t1 = Term(1)
    val command1 = "a"

    val t2 = Term(2)
    val command2 = "b"

    // when
    val frozenLog = replicatedLog
    replicatedLog += Entry(command1, t1, 0)
    replicatedLog += Entry(command2, t2, 1)

    // then
    frozenLog.entries should have length 0 // check for immutability
    replicatedLog.entries should have length 2
  }

  "append" should "append in the right order" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String](1)

    // when
    replicatedLog += Entry("a", Term(1), 0)
    replicatedLog += Entry("b", Term(1), 1)

    // then
    val commands = replicatedLog.entries.map(_.command).toList
    commands should equal (List("a", "b"))
  }

  it should "append with slicing some elements away (Leader forces us to drop some entries)" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String](1)
    replicatedLog += Entry("a", Term(1), 0)
    replicatedLog += Entry("D", Term(1), 1)
    replicatedLog += Entry("D", Term(1), 2)

    val entries =
      Entry("b", Term(1), 1) ::
      Entry("c", Term(1), 2) ::
      Nil

    // when
    replicatedLog = replicatedLog.append(entries, take = 1)

    // then
    replicatedLog.entries.map(_.command).toList should equal (List("a", "b", "c"))
  }

  it should "append properly" in {
    // given
    var replicatedLog = ReplicatedLog.empty[Cmnd](1)
    replicatedLog += Entry(AppendWord("I"), Term(1), 0)
    replicatedLog += Entry(AppendWord("like"), Term(1), 1)
    replicatedLog += Entry(AppendWord("bananas"), Term(1), 2)
    replicatedLog += Entry(GetWords, Term(1), 3)

    // when

    // then
  }

  "comittedEntries" should "contain entries up until the last committed one" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String](1)
    replicatedLog = ReplicatedLog.empty[String](1)
    replicatedLog = replicatedLog.append(Entry("a", Term(1), 0))
    replicatedLog = replicatedLog.append(Entry("b", Term(2), 1))
    replicatedLog = replicatedLog.append(Entry("a", Term(3), 2))

    // when
    val comittedIndex = 2
    val comittedLog = replicatedLog.commit(comittedIndex)

    // then
    replicatedLog.lastIndex should equal (comittedLog.lastIndex)
    replicatedLog.lastTerm should equal (comittedLog.lastTerm)

    replicatedLog.committedIndex should equal (-1) // nothing ever comitted
    comittedLog.committedIndex should equal (comittedIndex)

    comittedLog.committedEntries should have length (2)
    comittedLog.committedEntries.head should equal (Entry("a", Term(1), 0, None))
    comittedLog.committedEntries.tail.head should equal (Entry("b", Term(2), 1, None))
  }

  "isConsistentWith" should "be consistent for valid append within a term" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String](1)
    replicatedLog = replicatedLog.append(Entry("a", Term(1), 0)) // t1, 0
    replicatedLog = replicatedLog.append(Entry("b", Term(1), 1)) // t1, 1

    // when / then
    replicatedLog.containsMatchingEntry(Term(1), 0) should equal (false)
    replicatedLog.containsMatchingEntry(Term(1), 1) should equal (true)
  }

  it should "be consistent with itself, from 1 write in the past" in {
    // given
    val emptyLog = ReplicatedLog.empty[String](1)
    var replicatedLog = ReplicatedLog.empty[String](1)
    replicatedLog += Entry("a", Term(1), 0)

    // when
    info(s"empty log: ${emptyLog}")
    info(s"prevTerm: ${replicatedLog.prevTerm}, prevIndex: ${replicatedLog.prevIndex}")
    val isConsistent = emptyLog.containsMatchingEntry(replicatedLog.prevTerm, replicatedLog.prevIndex)

    // then
    isConsistent should equal (true)
  }

  it should "be consistent for initial append" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String](1)
    replicatedLog = replicatedLog.append(Entry("I", Term(1), 0))
    info("replicated log = " + replicatedLog)

    // when / then
    replicatedLog.containsMatchingEntry(Term(0), 0) should equal (true)
  }

  it should "be consistent with AppendEntries with multiple entries" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String](1)
    replicatedLog = replicatedLog.append(Entry("a", Term(1), 0))
    replicatedLog = replicatedLog.append(Entry("b", Term(1), 1))
    replicatedLog = replicatedLog.append(Entry("b", Term(2), 2))
    replicatedLog = replicatedLog.append(Entry("b", Term(3), 3))

    // when / then
    replicatedLog.containsMatchingEntry(Term(1), 0) should equal (false)
    replicatedLog.containsMatchingEntry(Term(1), 1) should equal (false)
    replicatedLog.containsMatchingEntry(Term(1), 2) should equal (false)
    replicatedLog.containsMatchingEntry(Term(2), 2) should equal (false)
    replicatedLog.containsMatchingEntry(Term(2), 3) should equal (false)
    replicatedLog.containsMatchingEntry(Term(3), 2) should equal (false)
    replicatedLog.containsMatchingEntry(Term(3), 3) should equal (true)
  }

  "prevTerm / prevIndex" should "be Term(0) / 0 after first write" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String](1)
    replicatedLog = replicatedLog.append(Entry("a", Term(1), 0))

    // when
    val prevTerm = replicatedLog.prevTerm
    val prevIndex = replicatedLog.prevIndex

    // then
    prevTerm should equal (Term(0))
    prevIndex should equal (0)
  }

  "entriesFrom" should "not include already sent entry, from given term" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String](1)
    replicatedLog = replicatedLog.append(Entry("a", Term(1), 0))
    replicatedLog = replicatedLog.append(Entry("b", Term(1), 1))
    replicatedLog = replicatedLog.append(Entry("c", Term(2), 2))
    replicatedLog = replicatedLog.append(Entry("d", Term(3), 3)) // other term

    // when
    val inTerm1 = replicatedLog.entriesBatchFrom(1)

    // then
    inTerm1 should have length 1
    inTerm1(0) should equal (Entry("b", Term(1), 1))
  }

  it should "not include already sent entries, from given term" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String](1)
    replicatedLog = replicatedLog.append(Entry("a", Term(1), 0))
    replicatedLog = replicatedLog.append(Entry("b", Term(1), 1))
    replicatedLog = replicatedLog.append(Entry("c0", Term(2), 2))
    replicatedLog = replicatedLog.append(Entry("c1", Term(2), 3))
    replicatedLog = replicatedLog.append(Entry("c2", Term(2), 4))
    replicatedLog = replicatedLog.append(Entry("d", Term(3), 5)) // other term

    // when
    val entriesFrom2ndTerm = replicatedLog.entriesBatchFrom(2)

    // then
    entriesFrom2ndTerm should have length 3
    entriesFrom2ndTerm(0) should equal (Entry("c0", Term(2), 2))
    entriesFrom2ndTerm(1) should equal (Entry("c1", Term(2), 3))
    entriesFrom2ndTerm(2) should equal (Entry("c2", Term(2), 4))
  }

  "verifyOrDrop" should "not change if entries match" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String](1)
    replicatedLog = replicatedLog.append(Entry("a", Term(1), 0))
    replicatedLog = replicatedLog.append(Entry("b", Term(1), 1))
    replicatedLog = replicatedLog.append(Entry("c", Term(2), 2))
    replicatedLog = replicatedLog.append(Entry("d", Term(3), 3))

    // when
    val check0 = replicatedLog.putWithDroppingInconsistent(Entry("a", Term(1), 0))
    val check1 = replicatedLog.putWithDroppingInconsistent(Entry("b", Term(1), 1))
    val check2 = replicatedLog.putWithDroppingInconsistent(Entry("c", Term(2), 2))
    val check3 = replicatedLog.putWithDroppingInconsistent(Entry("d", Term(3), 3))

    // then
    check0 should equal (replicatedLog)
    check1 should equal (replicatedLog)
    check2 should equal (replicatedLog)
    check3 should equal (replicatedLog)
  }

  it should "drop elements after an index that does not match" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String](1)
    replicatedLog = replicatedLog.append(Entry("a", Term(1), 0))
    replicatedLog = replicatedLog.append(Entry("b", Term(1), 1))
    replicatedLog = replicatedLog.append(Entry("c", Term(2), 2))
    replicatedLog = replicatedLog.append(Entry("d", Term(3), 3))

    // when
    val check0 = replicatedLog.putWithDroppingInconsistent(Entry("a", Term(1), 0))
    val check1 = replicatedLog.putWithDroppingInconsistent(Entry("b", Term(1), 1))
    val check2 = replicatedLog.putWithDroppingInconsistent(Entry("C!!!", Term(2), 1)) // different command

    // then
    check0 should equal (replicatedLog)
    check1 should equal (replicatedLog)

    check2 should not equal replicatedLog
    check2.entries.head.command should equal ("a")
    check2.entries.tail.head.command should equal ("C!!!")
    check2.entries should have length 2
  }

  "between" should "include 0th entry when asked between(-1, 0)" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String](1)
    val firstEntry = Entry("a", Term(1), 0)
    replicatedLog += firstEntry

    // when
    val initialEntry = replicatedLog.between(-1, 0)

    // then
    initialEntry.headOption should be ('defined)
    initialEntry.head should equal (firstEntry)
  }

  "compactedWith" should "compact the log" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String](1)
    (1 to 21) foreach { i =>
      replicatedLog += Entry(s"e-$i", Term(1 + i / 10), i)
    }

    info("replicatedLog.lastTerm = " + replicatedLog.lastTerm)
    info("replicatedLog.lastIndex = " + replicatedLog.lastIndex)

    // when
    // we compact and store a snapshot somewhere
    val meta = RaftSnapshotMetadata(Term(2), 18, ClusterConfiguration(Nil))
    val snapshot = RaftSnapshot(meta, "example")

    info(s"Snapshotting until: $meta")
    val compactedLog = replicatedLog compactedWith snapshot

    // then
    info("compactedLog = " + compactedLog)
    compactedLog.entries should have length 4

    compactedLog.lastIndex should equal (replicatedLog.lastIndex)
    compactedLog.lastTerm should equal (replicatedLog.lastTerm)
  }

}
