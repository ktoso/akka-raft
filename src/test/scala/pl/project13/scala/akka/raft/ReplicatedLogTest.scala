package pl.project13.scala.akka.raft

import org.scalatest._

class ReplicatedLogTest extends FlatSpec with Matchers {
  
  behavior of "ReplicatedLog"

  it should "should contain commands and terms when they were recieved by leader" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String]

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
    var replicatedLog = ReplicatedLog.empty[String]

    // when
    replicatedLog += Entry("a", Term(1), 0)
    replicatedLog += Entry("b", Term(1), 1)

    // then
    val commands = replicatedLog.entries.map(_.command).toList
    commands should equal (List("a", "b"))
  }

  "comittedEntries" should "contain entries up until the last committed one" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String]
    replicatedLog = ReplicatedLog.empty[String]
    replicatedLog = replicatedLog.append(Entry("a", Term(1), 0))
    replicatedLog = replicatedLog.append(Entry("b", Term(2), 1))
    replicatedLog = replicatedLog.append(Entry("a", Term(3), 2))

    // when
    val comittedIndex = 2
    val comittedLog = replicatedLog.commit(comittedIndex)

    // then
    replicatedLog.lastIndex should equal (comittedLog.lastIndex)
    replicatedLog.lastTerm should equal (comittedLog.lastTerm)

    replicatedLog.commitedIndex should equal (0) // nothing ever comitted
    comittedLog.commitedIndex should equal (comittedIndex)

    comittedLog.committedEntries should have length (2)
    comittedLog.committedEntries.head should equal (Entry("a", Term(1), 0, None))
    comittedLog.committedEntries.tail.head should equal (Entry("b", Term(2), 1, None))
  }

  "isConsistentWith" should "be consistent for valid append within a term" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String]
    replicatedLog = replicatedLog.append(Entry("a", Term(1), 0)) // t1, 0
    replicatedLog = replicatedLog.append(Entry("b", Term(1), 1)) // t1, 1

    // when / then
    replicatedLog.containsMatchingEntry(Term(1), 0) should equal (false)
    replicatedLog.containsMatchingEntry(Term(1), 1) should equal (true)
  }

  it should "be consistent with itself, from 1 write in the past" in {
    // given
    val emptyLog = ReplicatedLog.empty[String]
    var replicatedLog = ReplicatedLog.empty[String]
    replicatedLog += Entry("a", Term(1), 0)

    // when
    info(s"empty log: ${emptyLog}")
    info(s"prevTerm: ${replicatedLog.prevTerm}, prevIndex: ${replicatedLog.prevIndex}")
    val isConsistent = emptyLog.containsMatchingEntry(replicatedLog.prevTerm, replicatedLog.prevIndex)

    // then
    isConsistent should equal (true)
  }

  it should "be consistent for valid append across a term" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String]
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
    var replicatedLog = ReplicatedLog.empty[String]
    replicatedLog = replicatedLog.append(Entry("a", Term(1), 0))

    // when
    val prevTerm = replicatedLog.prevTerm
    val prevIndex = replicatedLog.prevIndex

    // then
    prevTerm should equal (Term(0))
    prevIndex should equal (0)
  }

  "entriesFrom" should "not include already sent entries" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String]
    replicatedLog = replicatedLog.append(Entry("a", Term(1), 0))
    replicatedLog = replicatedLog.append(Entry("b", Term(1), 1))
    replicatedLog = replicatedLog.append(Entry("c", Term(2), 2))
    replicatedLog = replicatedLog.append(Entry("d", Term(3), 3))

    // when
    val lastTwo = replicatedLog.entriesBatchFrom(2)

    // then
    lastTwo should have length 2
    lastTwo(0) should equal (Entry("c", Term(2), 2))
    lastTwo(1) should equal (Entry("d", Term(3), 3))
  }

  "verifyOrDrop" should "not change if entries match" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String]
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
    var replicatedLog = ReplicatedLog.empty[String]
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

}
