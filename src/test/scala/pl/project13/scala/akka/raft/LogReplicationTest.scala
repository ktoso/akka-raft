package pl.project13.scala.akka.raft

import pl.project13.scala.akka.raft.protocol._
import concurrent.duration._

class LogReplicationTest extends RaftSpec {

  behavior of "Log Replication"

  val memberCount = 5

  val timeout = 200.millis
  
  it should "apply the state machine in expected order" in {
    // given
    subscribeElectedLeader()
    awaitElectedLeader()
    infoMemberStates()

    // when
    // todo duplication in api msg types!!!
    leader.get ! ClientMessage(probe.ref, AppendWord("I", probe.ref))
    leader.get ! ClientMessage(probe.ref, AppendWord("like", probe.ref))
    leader.get ! ClientMessage(probe.ref, AppendWord("bananas", probe.ref))

    probe.expectMsg(timeout, "I")
    probe.expectMsg(timeout, "like")
    probe.expectMsg(timeout, "bananas")

    // then
    leader.get ! ClientMessage(probe.ref, GetWords(probe.ref))
    probe.expectMsg(timeout, List("I", "like", "bananas"))
  }

}
