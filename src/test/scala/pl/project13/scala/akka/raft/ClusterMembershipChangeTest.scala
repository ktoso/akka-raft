package pl.project13.scala.akka.raft

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
    val additionalActor = createActor(s"raft-member-${initialMembers + 1}")
    val newConfiguration = ClusterConfiguration(raftConfiguration.members + additionalActor) // 0, 1

    initialLeader ! ChangeConfiguration(newConfiguration)

    // the bellow assertions pass when the new config is committed,
    // but it's also interesting to see in the logs, if propagation goes on properly, no specific test there
    awaitEntryComitted(1)

    // then
    infoMemberStates()
    info("leader   : " + leaders().map(simpleName))
    info("candidate: " + candidates().map(simpleName))
    info("follower : " + followers().map(simpleName))
    info("")

    additionalActor.stateName should equal (Follower)

    eventually {
      infoMemberStates()
      leaders() should have length 1
      candidates() should have length 0
      followers() should have length 5
    }

    info("After adding raft-member-6, and configuration change: ")
    infoMemberStates()
  }

}
