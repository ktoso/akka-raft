package pl.project13.scala.akka.raft

import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Span}
import pl.project13.scala.akka.raft.protocol._

class ClusterMembershipChangeTest extends RaftSpec(callingThreadDispatcher = false) {

  behavior of "Cluster membership change"

  val initialMembers = 5

  // note: these run sequential, so when the 2 test runs, we already have a leader,
  // so we can kill it, and see it the cluster re-elects a new one properly

  it should "allow to add additional servers" in {
    // given
    subscribeElectedLeader()
    subscribeEntryComitted()

    awaitElectedLeader()

    info("Initial state: ")
    infoMemberStates()
    val initialLeader = leader()

    // when
    val additionalActor = createActor(s"member-${initialMembers + 1}")
    val newConfiguration = ClusterConfiguration(raftConfiguration.members + additionalActor) // 0, 1

    initialLeader ! ChangeConfiguration(newConfiguration)

    // the bellow assertions pass when the new config is committed,
    // but it's also interesting to see in the logs, if propagation goes on properly, no specific test there
    awaitEntryComitted(1)

    // then
    val leaderCount = leaders()
    val candidateCount = candidates()
    val followerCount = followers()

    infoMemberStates()
    info("leader   : " + leaderCount.map(simpleName))
    info("candidate: " + candidateCount.map(simpleName))
    info("follower : " + followerCount.map(simpleName))
    info("")

    additionalActor.stateName should equal (Follower)

    eventually {
      leaderCount should have length 1
      candidateCount should have length 0
      followerCount should have length 5
    }

    info("After adding member-6, and configuration change: ")
    infoMemberStates()
  }

}
