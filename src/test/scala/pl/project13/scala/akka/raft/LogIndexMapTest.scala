package pl.project13.scala.akka.raft

import akka.testkit.TestProbe
import scala.collection.immutable

class LogIndexMapTest extends RaftSpec {

  behavior of "LogIndexMap"

  def memberCount = 0

  "majority" should "find the index reached on the majority of members" in {
    // given
    val probe1, probe2, probe3 = TestProbe()

    val matchIndex = LogIndexMap.initialize(immutable.Seq(probe1.ref, probe2.ref, probe3.ref), 0)

    // given / then
    matchIndex.indexOnMajority should equal (0) // 0 0 0

    matchIndex.incrementFor(probe1.ref)
    matchIndex.indexOnMajority should equal (0) // 1 0 0

    matchIndex.incrementFor(probe2.ref)
    matchIndex.indexOnMajority should equal (1) // 1 1 0

    matchIndex.incrementFor(probe3.ref)
    matchIndex.indexOnMajority should equal (1) // 1 1 1

    matchIndex.incrementFor(probe3.ref)
    matchIndex.indexOnMajority should equal (1) // 1 1 2

    matchIndex.incrementFor(probe1.ref)
    matchIndex.indexOnMajority should equal (2) // 1 2 2
  }

}
