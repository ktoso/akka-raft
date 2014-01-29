package pl.project13.scala.akka.raft

import pl.project13.scala.akka.raft.protocol._
import concurrent.duration._
import akka.testkit.TestProbe

class LogReplicationTest extends RaftSpec(callingThreadDispatcher = false) {

  behavior of "Log Replication"

  val memberCount = 5

  val timeout = 2.second

  val client = TestProbe()

  it should "apply the state machine in expected order" in {
    // given
    subscribeElectedLeader()
    awaitElectedLeader()
    infoMemberStates()

    // when
    leader ! ClientMessage(client.ref, AppendWord("I"))       // 0
    leader ! ClientMessage(client.ref, AppendWord("like"))    // 1
    leader ! ClientMessage(client.ref, AppendWord("bananas")) // 2
    leader ! ClientMessage(client.ref, GetWords())            // 3

    // probe will get the messages once they're confirmed by quorum
    client.expectMsg(timeout, "I")
    client.expectMsg(timeout, "like")
    client.expectMsg(timeout, "bananas")

    // then
    val got = client.expectMsg(timeout, List("I", "like", "bananas"))
    info(s"Final replicated state machine state: $got")
  }

  it should "replicate the missing entries to Follower which was down for a while" in {
    // given
    infoMemberStates()

    // when
    val failingMembers = followers.take(3)

    failingMembers foreach { suspendMember(_) }

    leader ! ClientMessage(client.ref, AppendWord("and"))    // 4
    leader ! ClientMessage(client.ref, AppendWord("apples")) // 5

    // during this time it should not be able to respond...
    Thread.sleep(300)
    infoMemberStates()

    failingMembers foreach { restartMember(_) }
    leader ! MembersChanged(members)

    leader ! ClientMessage(client.ref, AppendWord("!"))      // 6

    // then
    // after all nodes came online again, raft should have been able to commit the messages!
    client.expectMsg(timeout, "and")
    client.expectMsg(timeout, "apples")
    client.expectMsg(timeout, "!")
  }

  it should "" in {
    
  }

}
