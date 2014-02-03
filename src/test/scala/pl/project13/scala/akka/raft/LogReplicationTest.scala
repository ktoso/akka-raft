package pl.project13.scala.akka.raft

import pl.project13.scala.akka.raft.protocol._
import concurrent.duration._
import akka.testkit.TestProbe
import pl.project13.scala.akka.raft.example.protocol._
import org.scalatest.concurrent.Eventually

class LogReplicationTest extends RaftSpec(callingThreadDispatcher = false)
  with Eventually {

  behavior of "Log Replication"

  val initialMembers = 5

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
    leader ! ClientMessage(client.ref, GetWords)              // 3

    // then
    client.expectMsg(timeout, "I")
    client.expectMsg(timeout, "like")
    client.expectMsg(timeout, "bananas")

    val got = client.expectMsg(timeout, List("I", "like", "bananas"))
    info(s"Final replicated state machine state: $got")
  }


  it should "replicate the missing entries to Follower which was down for a while" in {
    // given
    infoMemberStates()

    subscribeEntryComitted()

    // when
    val failingMembers = followers().take(3)
    val initialLeader = leader()
    initialLeader ! ChangeConfiguration(ClusterConfiguration(members().toSet -- failingMembers)) // 4, 5
    failingMembers foreach killMember

    awaitEntryComitted(5)
    infoMemberStates()

    initialLeader ! ClientMessage(client.ref, AppendWord("and"))    // 6
    initialLeader ! ClientMessage(client.ref, AppendWord("apples")) // 7

    // during this time it should not be able to respond...
    val revivedMembers = failingMembers.take(2)
    revivedMembers foreach restartMember

    val allMembers = members() ++ revivedMembers
    // actualy only the leader, and the failingMembers care
    allMembers foreach { _ ! ChangeConfiguration(ClusterConfiguration(allMembers)) } // 8, 9

    awaitEntryComitted(9)
    infoMemberStates()

    initialLeader ! ClientMessage(client.ref, AppendWord("!"))      // 10
    awaitEntryComitted(10)

    // then
    // after all nodes came online again, raft should have been able to commit the messages!
    eventually {
      client.expectMsg(timeout, "and")
      client.expectMsg(timeout, "apples")
      client.expectMsg(timeout, "!")
    }
  }

}
