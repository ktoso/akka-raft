package pl.project13.scala.akka.raft

import pl.project13.scala.akka.raft.protocol._
import concurrent.duration._

class LogReplicationTest extends RaftSpec {

  behavior of "Log Replication"

  val memberCount = 5

  it should "apply the state machine in expected order" in {
    // given
    subscribeElectedLeader()
    awaitElectedLeader()
    infoMemberStates()

    // when
    leader.get ! Write(probe.ref, "I")
    leader.get ! Write(probe.ref, "like")
    leader.get ! Write(probe.ref, "bananas")

    probe.expectMsg(max = 100.millis, "I")
    probe.expectMsg(max = 100.millis, "like")
    probe.expectMsg(max = 100.millis, "bananas")

    // then
    leader.get ! GetWords(probe.ref)
    probe.expectMsg(max = 100.millis, List("I", "like", "bananas"))
  }

}
