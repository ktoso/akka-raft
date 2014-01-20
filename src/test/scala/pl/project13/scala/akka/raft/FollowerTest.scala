package pl.project13.scala.akka.raft

import pl.project13.scala.akka.raft.protocol._
import akka.testkit.{TestProbe, TestFSMRef}
import akka.actor.Actor
import org.scalatest.BeforeAndAfterEach

class FollowerTest extends RaftSpec with BeforeAndAfterEach {

  behavior of "Follower"

  val follower = TestFSMRef(new RaftTestActor)

  var data: Meta = _
  
  val memberCount = 0

  override def beforeEach() {
    data = Meta.initial(follower)
      .copy(members = Vector(probe.ref))
  }

  it should "reply with Vote if Candidate has later Term than it" in {
    // given
    follower.setState(Follower, data)

    // when
    follower ! RequestVote(Term(1), probe.ref, Term(0), 0)

    // then
  }

}
