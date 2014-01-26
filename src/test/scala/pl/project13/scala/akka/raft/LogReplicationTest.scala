package pl.project13.scala.akka.raft

import pl.project13.scala.akka.raft.protocol._
import concurrent.duration._
import akka.testkit.TestProbe

class LogReplicationTest extends RaftSpec {

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

//  it should "replicate the missing entries to Follower which was down for a while" in {
//    // given
//    val client = TestProbe()
//    infoMemberStates()
//
//    val unstableMemberListener = TestProbe()
//
//    // when
//    val unstableFollower = followers.head
//    unstableMemberListener.ref ! ClientMessage(client.ref, AddListener(unstableMemberListener.ref))
//    Thread.sleep(100)
//
//    suspendMember(unstableFollower)
//    leader ! ClientMessage(client.ref, AppendWord("and", client.ref))
//    leader ! ClientMessage(client.ref, AppendWord("apples", client.ref))
//    Thread.sleep(150)
//
//    restartMember(unstableFollower)
//    Thread.sleep(200)
//    leader ! MembersChanged(members)
//
//    leader ! ClientMessage(client.ref, AppendWord("!", client.ref))
//    Thread.sleep(200)
//
//
//    // then
//    unstableMemberListener.expectMsg(ClientMessage(client.ref, AddListener(unstableMemberListener.ref)))
//    unstableMemberListener.expectMsg(timeout, "and")
//    unstableMemberListener.expectMsg(timeout, "apples")
//    unstableMemberListener.expectMsg(timeout, "!")
//  }

}
