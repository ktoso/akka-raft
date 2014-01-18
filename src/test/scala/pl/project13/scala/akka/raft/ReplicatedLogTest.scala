package pl.project13.scala.akka.raft

import org.scalatest.{FlatSpec, Matchers}

class ReplicatedLogTest extends FlatSpec with Matchers {
  
  behavior of "Log"

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
    frozenLog.entries should have length 1 // check for immutability
    replicatedLog.entries should have length 3
  }

}
