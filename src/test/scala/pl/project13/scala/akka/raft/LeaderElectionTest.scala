package pl.project13.scala.akka.raft

import pl.project13.scala.akka.raft.protocol._

class LeaderElectionTest extends RaftSpec {

  behavior of "Leader Election"

  val memberCount = 5

  // note: these run sequential, so when the 2 test runs, we already have a leader,
  // so we can kill it, and see it the cluster re-elects a new one properly

  it should "electe initial Leader" in {
    // given
    info("Before election: ")
    infoMemberStates()

    // when
    awaitElectedLeader()
    info("After election: ")
    infoMemberStates()

    // then
    members.count(_.stateName == Leader) should equal (1)
    members.count(_.stateName == Candidate) should equal (0)
    members.count(_.stateName == Follower) should equal (4)
  }

  it should "elect replacement Leader if current Leader dies" in {
    // given
    infoMemberStates()

    // when
    val leaderToStop = leader.get
    leaderToStop.stop()
    info(s"Stopped leader: ${simpleName(leaderToStop)}")

    // then
    awaitElectedLeader()
    info("New leader elected: ")
    infoMemberStates()

    members.count(_.stateName == Leader) should equal (1)
    members.count(_.stateName == Candidate) should equal (0)
    members.count(_.stateName == Follower) should equal (3)
  }

  it should "be able to maintain the same leader for a long time" in {
    // when
    val memberStates1 = members.sortBy(_.path.elements.last).map(_.stateName)
    Thread.sleep(50)
    val memberStates2 = members.sortBy(_.path.elements.last).map(_.stateName)
    Thread.sleep(50)
    val memberStates3 = members.sortBy(_.path.elements.last).map(_.stateName)
    Thread.sleep(50)
    val memberStates4 = members.sortBy(_.path.elements.last).map(_.stateName)

    info("Maintained state:")
    infoMemberStates()

    // then
    memberStates1 should equal (memberStates2)
    memberStates1 should equal (memberStates3)
    memberStates1 should equal (memberStates4)
  }

}
