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
  with BeforeAndAfterEach with BeforeAndAfterAll {

  behavior of "Leader"

  val leader = TestFSMRef(new WordConcatRaftActor)

  var data: LeaderMeta = _
  
  override def beforeEach() {
    super.beforeEach()
    data = Meta.initial(leader)
      .copy(
        currentTerm = Term(1),
        config = RaftConfiguration(leader)
      ).forNewElection.forLeader
  }

  it should "commit an entry once it has been written by the majority of the Followers" in {
    // given
    leader.setState(Leader, data)
    val actor = leader.underlyingActor

    val matchIndex = LogIndexMap.initialize(Set.empty, -1)
    matchIndex.put(TestProbe().ref, 2)
    matchIndex.put(TestProbe().ref, 2)
    matchIndex.put(TestProbe().ref, 1)

    var replicatedLog = actor.replicatedLog
    replicatedLog += model.Entry(AppendWord("a"), Term(1), 1)
    replicatedLog += model.Entry(AppendWord("b"), Term(1), 2)
    replicatedLog += model.Entry(AppendWord("c"), Term(1), 3)

    // when
    val (committedLog, data2) = actor.maybeCommitEntry(data, matchIndex, replicatedLog)
    data = data2 // todo super ugly, fixme

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
    expectMsg(ChangeConfiguration(StableRaftConfiguration(Set(leader))))
  }

}