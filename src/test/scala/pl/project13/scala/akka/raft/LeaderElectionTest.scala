package pl.project13.scala.akka.raft

import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import scala.concurrent.duration._

class LeaderElectionTest extends RaftSpec with PersistenceCleanup with Eventually {

  behavior of "Leader Election"

  override implicit val patienceConfig = PatienceConfig(timeout = scaled(Span(2, Seconds)), interval = scaled(Span(100, Millis)))

  val initialMembers = 5

  // note: these run sequential, so when the 2 test runs, we already have a leader,
  // so we can kill it, and see it the cluster re-elects a new one properly

  it should "elect initial Leader" in {
    // given
    subscribeBeginAsLeader()

    info("Before election: ")
    infoMemberStates()

    // when
    awaitBeginAsLeader()
    info("After election: ")
    infoMemberStates()

    // then
    eventually {
      leaders should have length 1
      candidates should have length 0
      followers should have length 4
    }
  }

  it should "elect replacement Leader if current Leader dies" in {
    // given
    subscribeBeginAsLeader()

    infoMemberStates()

    // when
    killLeader()

    // then
    awaitBeginAsLeader()
    info("New leader elected: ")
    infoMemberStates()

    eventually {
      leaders should have length 1
      candidates should have length 0
      followers should have length 3
    }
  }


  it should "be able to maintain the same leader for a long time" in {
    // given
    subscribeBeginAsLeader()

    // when
    val potentialLeaderOne = leaders.head
    Thread.sleep(400)
    val potentialLeaderTwo = leaders.head
    Thread.sleep(400)
    val potentialLeaderThree = leaders.head
    Thread.sleep(400)
    val potentialLeaderFour = leaders.head

    info("Maintained state:")
    infoMemberStates()

    // then
    potentialLeaderOne should equal (potentialLeaderTwo)
    potentialLeaderOne should equal (potentialLeaderThree)
    potentialLeaderOne should equal (potentialLeaderFour)
  }

  override def beforeAll(): Unit =
    subscribeClusterStateTransitions()
    super.beforeAll()
}
