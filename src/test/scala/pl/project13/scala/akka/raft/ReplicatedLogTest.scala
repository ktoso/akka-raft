package pl.project13.scala.akka.raft

import org.scalatest.{FlatSpec, Matchers}

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
    replicatedLog = replicatedLog.append(t1, None, command1)
    replicatedLog = replicatedLog.append(t2, None, command2)

    // then
    frozenLog.entries should have length 0 // check for immutability
    replicatedLog.entries should have length 2
  }

  "comittedEntries" should "contain entries up until the last committed one" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String]
    replicatedLog = ReplicatedLog.empty[String]
    replicatedLog = replicatedLog.append(Term(1), None, "a")
    replicatedLog = replicatedLog.append(Term(2), None, "b")
    replicatedLog = replicatedLog.append(Term(3), None, "a")

    // when
    val comittedLog = replicatedLog.commit(2)

    // then, should have thrown
    replicatedLog.lastIndex should equal (comittedLog.lastIndex)
    replicatedLog.lastTerm should equal (comittedLog.lastTerm)

    replicatedLog.commitedIndex should equal (0)
    comittedLog.commitedIndex should equal (2)

    comittedLog.committedEntries should have length (2)
    comittedLog.committedEntries.head should equal (Entry("a", Term(1), None))
    comittedLog.committedEntries.tail.head should equal (Entry("b", Term(2), None))
  }

  "isConsistentWith" should "be consistent for valid append within a term" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String]
    replicatedLog = replicatedLog.append(Term(1), None, "a") // t1, 0
    replicatedLog = replicatedLog.append(Term(1), None, "b") // t1, 1

    // when / then
    replicatedLog.isConsistentWith(Term(1), 0) should equal (false)
    replicatedLog.isConsistentWith(Term(1), 1) should equal (true)
  }

  it should "be consistent with itself, from 1 write in the past" in {
    // given
    val emptyLog = ReplicatedLog.empty[String]
    var replicatedLog = ReplicatedLog.empty[String]
    replicatedLog = replicatedLog.append(Term(1), None, "a") // t1, 0

    // when
    val isConsistent = emptyLog.isConsistentWith(replicatedLog.prevTerm, replicatedLog.prevIndex)

    // then
    isConsistent should equal (true)
  }
  it should "be consistent for valid append across a term" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String]
    replicatedLog = replicatedLog.append(Term(1), None, "a") // t1, 0
    replicatedLog = replicatedLog.append(Term(1), None, "b") // t1, 1
    replicatedLog = replicatedLog.append(Term(2), None, "b") // t2, 2
    replicatedLog = replicatedLog.append(Term(3), None, "b") // t3, 3

    // when / then
    replicatedLog.isConsistentWith(Term(1), 0) should equal (false)
    replicatedLog.isConsistentWith(Term(1), 1) should equal (false)
    replicatedLog.isConsistentWith(Term(1), 2) should equal (false)
    replicatedLog.isConsistentWith(Term(2), 2) should equal (false)
    replicatedLog.isConsistentWith(Term(2), 3) should equal (false)
    replicatedLog.isConsistentWith(Term(3), 2) should equal (false)
    replicatedLog.isConsistentWith(Term(3), 3) should equal (true)
  }

  "prevTerm / prevIndex" should "be Term(0) / -1 after first write" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String]
    replicatedLog = replicatedLog.append(Term(1), None, "a") // t1, 0

    // when
    val prevTerm = replicatedLog.prevTerm
    val prevIndex = replicatedLog.prevIndex

    // then
    prevTerm should equal (Term(0))
    prevIndex should equal (-1)
  }

  "entriesFrom" should "not include already sent entries" in {
    // given
    var replicatedLog = ReplicatedLog.empty[String]
    replicatedLog = replicatedLog.append(Term(1), None, "a") // t1, 0
    replicatedLog = replicatedLog.append(Term(1), None, "b") // t1, 1
    replicatedLog = replicatedLog.append(Term(2), None, "c") // t2, 2
    replicatedLog = replicatedLog.append(Term(3), None, "d") // t3, 3

    // when
    val lastTwo = replicatedLog.entriesFrom(0)

    // then
    lastTwo should have length 2
    lastTwo(0) should equal (Entry("c", Term(2)))
    lastTwo(1) should equal (Entry("d", Term(3)))
  }

}
