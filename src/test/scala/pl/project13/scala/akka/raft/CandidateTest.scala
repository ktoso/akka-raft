package pl.project13.scala.akka.raft

import pl.project13.scala.akka.raft.protocol._
import akka.testkit.{ImplicitSender, TestFSMRef}
import org.scalatest.BeforeAndAfterEach
import akka.actor.FSM.Event

class CandidateTest extends RaftSpec with BeforeAndAfterEach
  with ImplicitSender {

  behavior of "Candidate"

  val candidate = TestFSMRef(new RaftTestActor with EventStreamAllMessages)

  var data: ElectionMeta = _
  
  val memberCount = 0

  override def beforeEach() {
    super.beforeEach()
    data = Meta.initial(candidate)
      .copy(
        currentTerm = Term(2),
        members = Vector(self)
      ).forNewElection
  }

  it should "start a new election round if electionTimeout reached, and no one became Leader" in {
    // given
    subscribeBeginElection()

    candidate.setState(Candidate, data)

    // when
    awaitBeginElection()

    Thread.sleep(electionTimeoutMax.toMillis)
    Thread.sleep(electionTimeoutMax.toMillis)

    // then
    awaitBeginElection() // after a while, should trigger another one
    candidate.stateName should equal (Candidate)
  }

}
