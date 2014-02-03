package pl.project13.scala.akka.raft

import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Span}
import pl.project13.scala.akka.raft.protocol._

class ClusterMembershipChangeTest extends RaftSpec(callingThreadDispatcher = false)
  with Eventually {

  behavior of "Cluster membership change"

  override implicit val patienceConfig = PatienceConfig(timeout = scaled(Span(500, Millis)), interval = scaled(Span(100, Millis)))

  val initialMembers = 5

  // note: these run sequential, so when the 2 test runs, we already have a leader,
  // so we can kill it, and see it the cluster re-elects a new one properly

  it should "allow to add additional servers" in {
    // given
    subscribeElectedLeader()
    awaitElectedLeader()

    info("Initial state: ")
    infoMemberStates()
    val initialLeader = leader()

    // when
    val additionalActor = createActor(initialMembers + 1)
    val newConfiguration = ClusterConfiguration(raftConfiguration.members + additionalActor)

    initialLeader ! ChangeConfiguration(newConfiguration)

    // the bellow assertions pass when the new config is committed,
    // but it's also interesting to see in the logs, if propagation goes on properly, no specific test there
    Thread.sleep(1000)

    // then
    eventually {
      val leaderCount = members().count(_.stateName == Leader)
      val candidateCount = members().count(_.stateName == Candidate)
      val followerCount = members().count(_.stateName == Follower)

      follower("member-6").stateName should equal (Follower)

      leaderCount should equal (1)
      candidateCount should equal (0)
      followerCount should equal (5)
    }

    info("After adding member-6, and configuration change: ")
    infoMemberStates()
  }

}
