package pl.project13.scala.akka.raft

import pl.project13.scala.akka.raft.protocol._
import akka.testkit.{TestKit, TestProbe, TestFSMRef}
import org.scalatest._
import akka.actor.ActorSystem
import pl.project13.scala.akka.raft.example.{AppendWord, WordConcatRaftActor}
import pl.project13.scala.akka.raft.model.{Term, LogIndexMap}
import pl.project13.scala.akka.raft.model
import pl.project13.scala.akka.raft.protocol.RaftStates.Leader

class LeaderTest extends TestKit(ActorSystem("test-system")) with FlatSpecLike
  with Matchers
  with BeforeAndAfterEach with BeforeAndAfterAll {

  behavior of "Leader"

  val leader = TestFSMRef(new WordConcatRaftActor)

  var data: Meta = _
  
  override def beforeEach() {
    super.beforeEach()
    data = Meta.initial(leader)
      .copy(
        currentTerm = Term(1),
        members = List(leader)
      )
  }

  it should "commit an entry once it has been written by the majority of the Followers" in {
    // given
    leader.setState(Leader, data)
    val actor = leader.underlyingActor

    val matchIndex = LogIndexMap.initialize(List.empty, -1)
    matchIndex.put(TestProbe().ref, 2)
    matchIndex.put(TestProbe().ref, 2)
    matchIndex.put(TestProbe().ref, 1)

    var replicatedLog = actor.replicatedLog
    replicatedLog += model.Entry(AppendWord("a"), Term(1), 1)
    replicatedLog += model.Entry(AppendWord("b"), Term(1), 2)
    replicatedLog += model.Entry(AppendWord("c"), Term(1), 3)

    // when
    val committedLog = actor.maybeCommitEntry(matchIndex, replicatedLog)

    // then
    actor.replicatedLog.committedIndex should equal (-1)
    committedLog.committedIndex should equal (2)
  }

}