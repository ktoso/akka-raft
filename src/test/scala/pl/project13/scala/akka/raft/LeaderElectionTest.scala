package pl.project13.scala.akka.raft

import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import akka.testkit.TestKit
import akka.actor.ActorSystem
import pl.project13.scala.akka.raft.protocol._

class LeaderElectionTest extends RaftSpec {

  behavior of "Candidate"

  val memberCount = 3

  it should "be elected as Leader when recieved majority of votes" in {
    // given
    // when
    Thread.sleep(500)

    infoMemberStates()

    // then
    members.count(_.stateName == Leader) should equal (1)
    members.count(_.stateName == Candidate) should equal (0)
    members.count(_.stateName == Follower) should equal (4)
  }

}
