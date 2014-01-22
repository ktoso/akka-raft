package pl.project13.scala.akka.raft

import org.scalatest.{FlatSpec, Matchers}

class ReplicatedLogTest extends FlatSpec with Matchers {
  
  behavior of "ReplicatedLog"

  var replicatedLog = ReplicatedLog.empty[String]

  it should "should contain commands and terms when they were recieved by leader" in {
    // given
    val t1 = Term(1)
    val command1 = "a"

    val t2 = Term(2)
    val command2 = "b"

    // when
    val frozenLog = replicatedLog
    replicatedLog = replicatedLog.append(t1, command1, None)
    replicatedLog = replicatedLog.append(t2, command2, None)

    // then
    frozenLog.entries should have length 0 // check for immutability
    replicatedLog.entries should have length 2
  }

  "comittedEntries" should "contain entries up until the last committed one" in {
    // given
    replicatedLog = ReplicatedLog.empty[String]
    replicatedLog = replicatedLog.append(Term(1), "a", None)
    replicatedLog = replicatedLog.append(Term(2), "b", None)
    replicatedLog = replicatedLog.append(Term(3), "a", None)

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

}
