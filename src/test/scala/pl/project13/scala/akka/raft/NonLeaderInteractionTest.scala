package pl.project13.scala.akka.raft

import pl.project13.scala.akka.raft.protocol._
import concurrent.duration._
import akka.testkit.TestProbe
import pl.project13.scala.akka.raft.example.protocol._
import org.scalatest.concurrent.Eventually
import org.scalatest.GivenWhenThen

class NonLeaderInteractionTest extends RaftSpec(callingThreadDispatcher = false)
  with GivenWhenThen {

  behavior of "Non Leader Interaction"

  val initialMembers = 5

  val client = TestProbe()

  it should "allow contacting a non-leader member, which should respond with the Leader's ref" in {
    Given("a leader is elected")
    subscribeElectedLeader()
    awaitElectedLeader()
    infoMemberStates()

    val msg = ClientMessage(client.ref, AppendWord("test"))

    subscribeEntryComitted()
    leader() ! ClientMessage(self, AppendWord("first"))

    val follower = followers().head

    awaitEntryComitted(0)
    expectMsg("first")

    When(s"the client sends a write message to a non-leader member (${simpleName(follower)})")
    follower ! msg

    Then("that non-leader, should respons with the leader's ref")
    val leaderIs = within(DefaultTimeoutDuration) {
      expectMsgType[LeaderIs].ref.get // we ask until we get the leader back
    }

    When("the client contact that member")
    leaderIs ! msg

    Then("the leader should take the write")
    within(DefaultTimeoutDuration) {
      client.expectMsg("test")
    }
  }

}
