package pl.project13.scala.akka.raft

import pl.project13.scala.akka.raft.protocol._

class LeaderElectionTest extends RaftSpec {

  behavior of "Leader Election"

  val memberCount = 5

  // note: these run sequential, so when the 2 test runs, we already have a leader,
  // so we can kill it, and see it the cluster re-elects a new one properly

  it should "be elected as Leader when recieved majority of votes" in {
    // given
    awaitElectedLeader()

    // when

    infoMemberStates()

    // then
    members.count(_.stateName == Leader) should equal (1)
    members.count(_.stateName == Candidate) should equal (0)
    members.count(_.stateName == Follower) should equal (4)
  }

  it should "elect a new leader if the initial one dies" in {
    // given
    infoMemberStates()

    // when
    val leaderToStop = leader.get
    leaderToStop.stop()
    info(s"Stopped leader: ${simpleName(leaderToStop)}")

    // then
    awaitElectedLeader()
    infoMemberStates()

    members.count(_.stateName == Leader) should equal (1)
    members.count(_.stateName == Candidate) should equal (0)
    members.count(_.stateName == Follower) should equal (3)
  }

}
