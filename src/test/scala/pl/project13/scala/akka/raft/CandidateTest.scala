package pl.project13.scala.akka.raft

import pl.project13.scala.akka.raft.protocol._
import akka.testkit.{ImplicitSender, TestFSMRef}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Span}
import pl.project13.scala.akka.raft.example.WordConcatRaftActor
import pl.project13.scala.akka.raft.model.{Entry, Term}
import pl.project13.scala.akka.raft.example.protocol._

class CandidateTest extends RaftSpec with BeforeAndAfterEach
  with Eventually
  with ImplicitSender {

  behavior of "Candidate"

  val candidate = TestFSMRef(new SnapshottingWordConcatRaftActor with EventStreamAllMessages)

  var data: ElectionMeta = _
  
  val initialMembers = 0

  override def beforeEach() {
    super.beforeEach()
    data = Meta.initial(candidate)
      .copy(
        currentTerm = Term(2),
        config = ClusterConfiguration(self)
      ).forNewElection
  }

  it should "start a new election round if electionTimeout reached, and no one became Leader" in {
    // given
    subscribeBeginElection()

    candidate.setState(Candidate, data)
    candidate.underlyingActor.resetElectionDeadline()

    // when
    awaitBeginElection()

    Thread.sleep(electionTimeoutMin.toMillis)
    Thread.sleep(electionTimeoutMin.toMillis)

    // then
    awaitBeginElection() // after a while, should trigger another one
    eventually {
      candidate.stateName should equal (Candidate)
    }
  }

  it should "go back to Follower state if got message from elected Leader (from later Trerm)" in {
    // given
    subscribeBeginElection()

    implicit val patienceConfig = PatienceConfig(timeout = scaled(Span(300, Millis)), interval = scaled(Span(50, Millis)))

    val entry = Entry(AppendWord("x"), Term(3), 5)
    candidate.setState(Candidate, data)

    // when
    candidate ! AppendEntries(Term(3), Term(2), 6, entry :: Nil, 5)

    // then
    eventually {
      // should have reverted to Follower
      candidate.stateName === Follower

      // and applied the message in Follower
      candidate.underlyingActor.replicatedLog.entries contains entry
    }
  }

}
