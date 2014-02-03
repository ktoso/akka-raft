package pl.project13.scala.akka.raft.model

import akka.testkit.TestProbe
import pl.project13.scala.akka.raft.{ClusterConfiguration, RaftSpec}
import org.scalatest.BeforeAndAfter

class LogIndexMapTest extends RaftSpec with BeforeAndAfter {

  behavior of "LogIndexMap"

  val probe1, probe2, probe3 = TestProbe()
  val probes = Set(probe1.ref, probe2.ref, probe3.ref)

  var matchIndex: LogIndexMap = _

  before {
    matchIndex = LogIndexMap.initialize(probes, 0)
  }

  def initialMembers = 0

  "consensus" should "be achieved on the majority of nodes, if in stable configuration state" in {
    // given
    val stableConfig = ClusterConfiguration(probes)

    // when / then
    matchIndex.put(probe1.ref, 1)
    matchIndex.put(probe2.ref, 1)
    matchIndex.put(probe3.ref, 2)
    matchIndex.consensusForIndex(stableConfig) should equal (1)

    matchIndex.put(probe1.ref, 2)
    matchIndex.put(probe2.ref, 1)
    matchIndex.put(probe3.ref, 2)
    matchIndex.consensusForIndex(stableConfig) should equal (2)

    matchIndex.put(probe1.ref, 2)
    matchIndex.put(probe2.ref, 2)
    matchIndex.put(probe3.ref, 2)
    matchIndex.consensusForIndex(stableConfig) should equal (2)

    matchIndex.put(probe1.ref, 2)
    matchIndex.put(probe2.ref, 2)
    matchIndex.put(probe3.ref, 3)
    matchIndex.consensusForIndex(stableConfig) should equal (2)
  }

  it should "be achieved on the both majorities in case of an JointConsensus configuration" in {
    // given
    val probe4 = TestProbe()
    val probe5 = TestProbe()

    val initialConfig = ClusterConfiguration(probes)
    val nextConfig = ClusterConfiguration(probes + probe4.ref + probe5.ref)

    val jointConsensusConfig = initialConfig.transitionTo(nextConfig)

    // when / then
    // hint: old / new groups are visible by color ;-)
    matchIndex.put(probe1.ref, 1)
    matchIndex.put(probe2.ref, 2)
    matchIndex.put(probe3.ref, 2)
    matchIndex.put(probe4.ref, 1)
    matchIndex.put(probe5.ref, 1)
    // new has not yet cought up to 2
    matchIndex.consensusForIndex(jointConsensusConfig) should equal (1)

    matchIndex.put(probe1.ref, 1)
    matchIndex.put(probe2.ref, 2)
    matchIndex.put(probe3.ref, 2)
    matchIndex.put(probe4.ref, 2)
    matchIndex.put(probe5.ref, 2)
    // new has cought up to 2
    matchIndex.consensusForIndex(jointConsensusConfig) should equal (2)

    matchIndex.put(probe1.ref, 3)
    matchIndex.put(probe2.ref, 2)
    matchIndex.put(probe3.ref, 2)
    matchIndex.put(probe4.ref, 3)
    matchIndex.put(probe5.ref, 3)
    // new is faster than old
    matchIndex.consensusForIndex(jointConsensusConfig) should equal (2)

    matchIndex.put(probe1.ref, 3)
    matchIndex.put(probe2.ref, 2)
    matchIndex.put(probe3.ref, 5)
    matchIndex.put(probe4.ref, 3)
    matchIndex.put(probe5.ref, 2)
    // chaotic case, lowest must win
    matchIndex.consensusForIndex(jointConsensusConfig) should equal (2)
  }

}
