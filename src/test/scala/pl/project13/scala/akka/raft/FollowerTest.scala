package pl.project13.scala.akka.raft

import akka.testkit.ImplicitSender
import org.scalatest.BeforeAndAfterEach
import pl.project13.scala.akka.raft.protocol._

class FollowerTest extends RaftSpec with BeforeAndAfterEach
  with ImplicitSender {

  behavior of "Follower"

  val initialMembers = 3


  it should "reply with Vote if Candidate has later Term than it" in {
    // given

    subscribeBeginAsFollower()

    info("Waiting for the follower...")
    val msg = awaitBeginAsFollower()

    val follower = msg.ref
    val msgTerm = msg.term
    info(s"Member $follower become a follower in $msgTerm")

    val nextTerm = msgTerm.next
    info(s"Requesting vote from member in a higher term $nextTerm...")
    follower ! RequestVote(nextTerm, self, msgTerm, 2)

    fishForMessage() {
      case msg @ VoteCandidate(term) if term == nextTerm => true
      case _ => false
    }
  }


  it should "Reject if Candidate has lower Term than it" in {
    // given

    restartMember()
    subscribeBeginAsFollower()

    info("Waiting for the follower...")
    val msg = awaitBeginAsFollower()
    val follower = msg.ref
    val msgTerm = msg.term

    info(s"Member $follower become a follower in $msgTerm")
    val prevTerm = msgTerm.prev

    // when
    info(s"Requesting vote from member with a stale term $prevTerm...")
    follower ! RequestVote(prevTerm, self, prevTerm, 1)

    // then
    expectMsg(DeclineCandidate(msg.term))
  }

  it should "only vote once during a Term" in {
    // given

    restartMember()
    subscribeBeginAsFollower()

    info("Waiting for the follower...")
    val msg = awaitBeginAsFollower()
    val follower = msg.ref
    val msgTerm = msg.term

    info(s"Member $follower become a follower in $msgTerm")

    // when / then
    info(s"Requesting vote from member within current term $msgTerm for the first time")
    follower ! RequestVote(msg.term, self, msg.term, 2)
    expectMsg(VoteCandidate(msg.term))

    info(s"Requesting vote from member within current term $msgTerm for the second time")
    follower ! RequestVote(msg.term, self, msg.term, 2)
    expectMsg(DeclineCandidate(msg.term))
  }


  it should "update term number after getting a request with higher term number" in {

    restartMember()
    subscribeBeginAsFollower()
    subscribeTermUpdated()

    info("Waiting for the follower...")
    val msg = awaitBeginAsFollower()
    val follower = msg.ref
    val msgTerm = msg.term

    info(s"Member $follower become a follower in $msgTerm")


    val nextTerm = msgTerm.next

    info(s"Requesting vote from member with a newer term $nextTerm")
    follower ! RequestVote(nextTerm, self, nextTerm, 3)
    expectMsg(VoteCandidate(nextTerm))

    probe.fishForMessage() {
      case TermUpdated(term, actor) if term == nextTerm && actor == follower => true
      case _ => false
    }
  }

  it should "become a Candidate if the electionTimeout has elapsed" in {
    // given

    restartMember()
    subscribeBeginAsFollower()
    subscribeBeginElection()

    // when
    info("Waiting for the follower...")
    val msg = awaitBeginAsFollower()
    val follower = msg.ref
    val term = msg.term

    info(s"Member $follower become a follower in $term")

    info("After awaiting for election timeout...")
    probe.fishForMessage() {
      case ElectionStarted(_, actor) if actor == follower => true
      case _ => false
    }
  }


  /*it should "amortize taking the same write twice, the log should not contain duplicates then" in {
    restartMember()
    // given

    subscribeBeginAsFollower()
    val m = awaitBeginAsFollower()
    val follower = m.ref
    val t = m.term
    info(s"$t")

    val msg = AppendEntries(t, t, 1, immutable.Seq(Entry("a", t, 1)), 0)

    // when
    info("Sending Append(a)")
    follower ! msg

    info("Sending Append(a)")
    follower ! msg

    // then
    expectMsg(AppendSuccessful(t, 1))
    expectMsg(AppendSuccessful(t, 1))
  }*/

}
