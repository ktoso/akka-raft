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
    subscribeElectedLeader()
    awaitElectedLeader()
    infoMemberStates()

    // when
    // todo duplication in api msg types!!!
    leader ! ClientMessage(probe.ref, AppendWord("I", probe.ref))
    leader ! ClientMessage(probe.ref, AppendWord("like", probe.ref))
    leader ! ClientMessage(probe.ref, AppendWord("bananas", probe.ref))

    // probe will get the messages once they're confirmed by quorum
    probe.expectMsg(timeout, "I")
    probe.expectMsg(timeout, "like")
    probe.expectMsg(timeout, "bananas")

    // then
    leader ! ClientMessage(probe.ref, GetWords(probe.ref))
    probe.expectMsg(timeout, List("I", "like", "bananas"))
  }

  it should "replicate the missing entries to Follower which was down for a while" in {
    // given
    infoMemberStates()

    val unstableMemberListener = TestProbe()

    // when
    val unstableFollower = followers.head
    unstableMemberListener.ref ! ClientMessage(probe.ref, AddListener(unstableMemberListener.ref))
    Thread.sleep(100)

    suspendMember(unstableFollower)
    leader ! ClientMessage(probe.ref, AppendWord("and", probe.ref))
    leader ! ClientMessage(probe.ref, AppendWord("apples", probe.ref))
    Thread.sleep(150)

    restartMember(unstableFollower)
    Thread.sleep(200)
    leader ! MembersChanged(members)

    leader ! ClientMessage(probe.ref, AppendWord("!", probe.ref))
    Thread.sleep(200)


    // then
    unstableMemberListener.expectMsg(ClientMessage(probe.ref, AddListener(unstableMemberListener.ref)))
    unstableMemberListener.expectMsg(timeout, "and")
    unstableMemberListener.expectMsg(timeout, "apples")
    unstableMemberListener.expectMsg(timeout, "!")
  }

}
