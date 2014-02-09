package pl.project13.scala.akka.raft

import pl.project13.scala.akka.raft.protocol._
import akka.testkit.{ImplicitSender, TestProbe, TestFSMRef}
import org.scalatest.{OneInstancePerTest, BeforeAndAfterEach}
import concurrent.duration._
import scala.collection.immutable
import pl.project13.scala.akka.raft.example.WordConcatRaftActor
import pl.project13.scala.akka.raft.model.{Entry, Term}

class FollowerTest extends RaftSpec with BeforeAndAfterEach
  with ImplicitSender {

  behavior of "Follower"

  val follower = TestFSMRef(new SnapshottingWordConcatRaftActor)

  var data: Meta = _
  
  val initialMembers = 0

  override def beforeEach() {
    super.beforeEach()
    data = Meta.initial(follower)
      .copy(
        currentTerm = Term(2),
        config = ClusterConfiguration(self)
      )

    follower.underlyingActor.resetElectionDeadline()
  }

  it should "reply with Vote if Candidate has later Term than it" in {
    // given
    follower.setState(Follower, data)

    // when
    follower ! RequestVote(Term(2), self, Term(2), 2)

    // then
    expectMsg(VoteCandidate(Term(2)))
  }

  it should "Reject if Candidate has lower Term than it" in {
    // given
    follower.setState(Follower, data)

    // when
    follower ! RequestVote(Term(1), self, Term(1), 1)

    // then
    expectMsg(DeclineCandidate(Term(2)))
  }

  it should "only vote once during a Term" in {
    // given
    follower.setState(Follower, data)

    // when / then
    follower ! RequestVote(Term(2), self, Term(2), 2)
    expectMsg(VoteCandidate(Term(2)))

    follower ! RequestVote(Term(2), self, Term(2), 2)
    expectMsg(DeclineCandidate(Term(2)))
  }

  it should "become a Candidate if the electionTimeout has elapsed" in {
    // given
    follower.setState(Follower, data)

    // when
    info("After awaiting for election timeout...")

    // then
    eventually {
      follower.stateName should equal (Candidate)
    }
  }

  it should "amortize taking the same write twice, the log should not contain duplicates then" in {
    // given
    data = Meta.initial(follower)
      .copy(
        currentTerm = Term(0),
        config = ClusterConfiguration(self)
      )
    follower.setState(Follower, data)

    val msg = AppendEntries(Term(1), Term(0), 0, immutable.Seq(Entry("a", Term(1), 1)), -1)

    // when
    info("Sending Append(a)")
    follower.tell(msg, probe.ref)

    info("Sending Append(a)")
    follower.tell(msg, probe.ref)

    // then
    probe.expectMsg(AppendSuccessful(Term(1), 1))
    probe.expectMsg(AppendSuccessful(Term(1), 1))
  }

}
