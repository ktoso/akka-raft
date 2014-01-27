package pl.project13.scala.akka.raft

import pl.project13.scala.akka.raft.protocol._
import concurrent.duration._
import akka.testkit.TestProbe

class LogReplicationTest extends RaftSpec(callingThreadDispatcher = false) {

  behavior of "Log Replication"

  val memberCount = 5

  val timeout = 200.millis
  
  it should "apply the state machine in expected order" in {
    // given
    val client = TestProbe()
    
    subscribeElectedLeader()
    awaitElectedLeader()
    infoMemberStates()

    // when
    // todo duplication in api msg types!!!
    leader ! ClientMessage(client.ref, AppendWord("I"))
    leader ! ClientMessage(client.ref, AppendWord("like"))
    leader ! ClientMessage(client.ref, AppendWord("bananas"))

    // probe will get the messages once they're confirmed by quorum
    client.expectMsg(timeout, "I")
    client.expectMsg(timeout, "like")
    client.expectMsg(timeout, "bananas")

    // then
    leader ! ClientMessage(client.ref, GetWords())
    val got = client.expectMsg(timeout, List("I", "like", "bananas"))
    info(s"Final replicated state machine state: $got")
  }

  it should "replicate the missing entries to Follower which was down for a while" in {
    // given
    val client = TestProbe()
    infoMemberStates()

    // when
    val failingMembers = followers.take(2)
    
    failingMembers foreach { suspendMember(_) }

    leader ! ClientMessage(client.ref, AppendWord("and"))
    leader ! ClientMessage(client.ref, AppendWord("apples"))

    // during this time it should not be able to respond...
//    Thread.sleep(10000)

    failingMembers foreach { restartMember(_) }
    leader ! MembersChanged(members)

    leader ! ClientMessage(client.ref, AppendWord("!"))

    // then
    // after all nodes came online again, raft should have been able to commit the messages!
    client.expectMsg(timeout, "and")
    client.expectMsg(timeout, "apples")
    client.expectMsg(timeout, "!")
  }

}
