package pl.project13.scala.akka.raft

import akka.testkit.TestProbe
import org.scalatest.concurrent.Eventually
import pl.project13.scala.akka.raft.example.protocol._
import pl.project13.scala.akka.raft.protocol._

import scala.concurrent.duration._

class LogReplicationTest extends RaftSpec
  with Eventually with PersistenceCleanup {

  behavior of "Log Replication"

  val initialMembers = 5

  val timeout = 2.second

  val client = TestProbe()

  it should "apply the state machine in expected order" in {
    // given
    subscribeBeginAsLeader()
    val msg = awaitBeginAsLeader()
    val leader = msg.ref
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
    info("Final replicated state machine state: " + got)
  }

  // This test is commented because its logic is broken - its doesn't take cluster membership changes into account. However, it worked somehow due to Thread.sleep previously.

  /*it should "replicate the missing entries to Follower which was down for a while" in {
    // given
    infoMemberStates()
    subscribeEntryComitted()

    // when
    val failingMembers = followers.take(3)
    val initialLeader = leaders.head
    info(s"Leader $initialLeader")
    initialLeader ! ChangeConfiguration(StableClusterConfiguration(5, members.toSet -- failingMembers)) // 4, 5
    failingMembers foreach killMember

    //implicit val probe = TestProbe()

    awaitEntryComitted(5)
    infoMemberStates()

    initialLeader ! ClientMessage(client.ref, AppendWord("and"))    // 6
    initialLeader ! ClientMessage(client.ref, AppendWord("apples")) // 7

    // during this time it should not be able to respond...
    val revivedMembers = failingMembers.take(2)
    revivedMembers foreach { member =>
      restartMember(Some(member))
    }

    val allMembers = members ++ revivedMembers
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

    /*Thread.sleep(1000)
    eventually {
      val logs = members() map { _.underlyingActor.replicatedLog.entries }

      logs(0) === logs(1)
      logs(1) === logs(2)
      logs(2) === logs(3)
      info("Logs of all Raft members are equal!")
    }*/
  }*/

  override def beforeAll(): Unit =
    subscribeClusterStateTransitions()
    super.beforeAll()
}
