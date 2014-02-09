package pl.project13.scala.akka.raft

import pl.project13.scala.akka.raft.protocol._
import akka.testkit.{ImplicitSender, TestKit, TestProbe, TestFSMRef}
import org.scalatest._
import akka.actor.ActorSystem
import pl.project13.scala.akka.raft.example.WordConcatRaftActor
import pl.project13.scala.akka.raft.model.{Term, LogIndexMap}
import pl.project13.scala.akka.raft.example.protocol._

class LeaderTest extends TestKit(ActorSystem("test-system")) with FlatSpecLike with Matchers
  with ImplicitSender
  with BeforeAndAfter with BeforeAndAfterAll {

  behavior of "Leader"

  val leader = TestFSMRef(new SnapshottingWordConcatRaftActor)

  var data: LeaderMeta = _
  
  before {
    data = Meta.initial(leader)
      .copy(
        currentTerm = Term(1),
        config = ClusterConfiguration(leader)
      ).forNewElection.forLeader
  }

  it should "commit an entry once it has been written by the majority of the Followers" in {
    // given
    val probe1, probe2, probe3 = TestProbe().ref

    data = data.copy(config = ClusterConfiguration(probe1, probe2, probe3))
    leader.setState(Leader, data)
    val actor = leader.underlyingActor

    val matchIndex = LogIndexMap.initialize(Set.empty, -1)
    matchIndex.put(probe1, 2)
    matchIndex.put(probe2, 2)
    matchIndex.put(probe3, 1)

    var replicatedLog = actor.replicatedLog
    replicatedLog += model.Entry(AppendWord("a"), Term(1), 1)
    replicatedLog += model.Entry(AppendWord("b"), Term(1), 2)
    replicatedLog += model.Entry(AppendWord("c"), Term(1), 3)

    // when
    val committedLog = actor.maybeCommitEntry(data, matchIndex, replicatedLog)

    // then
    actor.replicatedLog.committedIndex should equal (-1)
    committedLog.committedIndex should equal (2)
  }

  it should "reply with it's current configuration when asked to" in {
    // note: this is used when an actor has died and starts again in Init state

    // given
    leader.setState(Leader, data)

    // when
    leader ! RequestConfiguration

    // then
    expectMsg(ChangeConfiguration(StableClusterConfiguration(0, Set(leader))))
  }

}