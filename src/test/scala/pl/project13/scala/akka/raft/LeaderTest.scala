package pl.project13.scala.akka.raft

import pl.project13.scala.akka.raft.protocol._
import akka.testkit.{TestKit, TestProbe, ImplicitSender, TestFSMRef}
import org.scalatest._
import scala.collection.immutable
import akka.actor.ActorSystem
import pl.project13.scala.akka.raft.Entry
import pl.project13.scala.akka.raft.AppendWord

class LeaderTest extends TestKit(ActorSystem("test-system")) with FlatSpecLike
  with Matchers
  with BeforeAndAfterEach with BeforeAndAfterAll {

  behavior of "Leader"

  val leader = TestFSMRef(new WordConcatRaftStateMachineActor)

  var data: Meta = _
  
  override def beforeEach() {
    super.beforeEach()
    data = Meta.initial(leader)
      .copy(
        currentTerm = Term(1),
        members = Vector(leader)
      )
  }

  it should "commit an entry once it has been written by the majority of the Followers" in {
    // given
    leader.setState(Leader, data)
    val actor = leader.underlyingActor

    val matchIndex = LogIndexMap.initialize(Vector.empty, -1)
    matchIndex.put(TestProbe().ref, 2)
    matchIndex.put(TestProbe().ref, 2)
    matchIndex.put(TestProbe().ref, 1)

    var replicatedLog = actor.replicatedLog
    replicatedLog += Entry(AppendWord("a"), Term(1), 1)
    replicatedLog += Entry(AppendWord("b"), Term(1), 2)
    replicatedLog += Entry(AppendWord("c"), Term(1), 3)

    // when
    val commitedLog = actor.maybeCommitEntry(matchIndex, replicatedLog)

    // then
    actor.replicatedLog.commitedIndex should equal (-1)
    commitedLog.commitedIndex should equal (2)
  }

}