package pl.project13.scala.akka.raft

import pl.project13.scala.akka.raft.protocol._
import akka.testkit.{ImplicitSender, TestProbe, TestFSMRef}
import akka.actor.Actor
import org.scalatest.{OneInstancePerTest, BeforeAndAfterEach}
import concurrent.duration._

class FollowerTest extends RaftSpec with BeforeAndAfterEach
  with ImplicitSender {

  behavior of "Follower"

  val follower = TestFSMRef(new RaftTestActor)

  var data: Meta = _
  
  val memberCount = 0

  override def beforeEach() {
    super.beforeEach()
    data = Meta.initial(follower)
      .copy(
        currentTerm = Term(2),
        members = Vector(self)
      )
  }

  it should "reply with Vote if Candidate has later Term than it" in {
    // given
    follower.setState(Follower, data)

    // when
    follower ! RequestVote(Term(2), self, Term(2), 2)

    // then
    expectMsg(Vote(Term(2)))
  }

  it should "Reject if Cancidate has lower Term than it" in {
    // given
    follower.setState(Follower, data)

    // when
    follower ! RequestVote(Term(1), self, Term(1), 1)

    // then
    expectMsg(Reject(Term(2)))
  }

  it should "only vote once during a Term" in {
    // given
    follower.setState(Follower, data)

    // when / then
    follower ! RequestVote(Term(2), self, Term(2), 2)
    expectMsg(Vote(Term(2)))

    follower ! RequestVote(Term(2), self, Term(2), 2)
    expectMsg(Reject(Term(2)))
  }

  it should "become a Candidate if the electionTimeout has elapsed" in {
    // given
    follower.setState(Follower, data)

    // when
    info("After awaiting for election timeout...")
    Thread.sleep(electionTimeoutMax.toMillis)

    // then
    follower.stateName should equal (Candidate)
  }

}
