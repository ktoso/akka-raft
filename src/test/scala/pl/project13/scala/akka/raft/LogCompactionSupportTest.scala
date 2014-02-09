package pl.project13.scala.akka.raft

import pl.project13.scala.akka.raft.protocol._
import concurrent.duration._
import akka.testkit.{TestFSMRef, TestProbe}
import pl.project13.scala.akka.raft.example.protocol._
import org.scalatest.concurrent.Eventually
import akka.fsm.hack.TestFSMRefHack
import akka.actor.Props
import pl.project13.scala.akka.raft.model.{Term, RaftSnapshot, RaftSnapshotMetadata}
import scala.concurrent.Future

class SnapshottingWordConcatRaftActor extends RaftActor {

  type Command = Cmnd

  var words = Vector[String]()

  /** Called when a command is determined by Raft to be safe to apply */
  def apply = {
    case AppendWord(word) =>
      words = words :+ word
      log.info(s"Applied command [AppendWord($word)], full words is: $words")

      word

    case GetWords =>
      log.info("Saving snapshot: " + words.toList)
      self ! InitLogSnapshot

      log.info("Replying with {}", words.toList)
      words.toList

    case InstallSnapshot(snapshot) =>
      log.info("Installing snapshot with meta: {}, value: {}", snapshot.meta, snapshot.data)
      words = snapshot.data.asInstanceOf[Vector[String]]

  }

  override def prepareSnapshot(meta: RaftSnapshotMetadata) =
    Future.successful(Some(RaftSnapshot(meta, words)))

}


class LogCompactionTest extends RaftSpec(callingThreadDispatcher = false)
  with Eventually {

  behavior of "Log Compaction in Members"

  val initialMembers = 3

  val timeout = 2.second

  val client = TestProbe()

  override def createActor(name: String): TestFSMRef[RaftState, Metadata, SnapshottingWordConcatRaftActor] = {
    val actor = TestFSMRefHack[RaftState, Metadata, SnapshottingWordConcatRaftActor](
      Props(new SnapshottingWordConcatRaftActor with EventStreamAllMessages).withDispatcher("raft-dispatcher"),
      name = name
    )
    _members :+= actor
    actor
  }

  it should "compact the log on all Members when Leader writes a snapshot" in {
    // given
    subscribeElectedLeader()
    awaitElectedLeader()
    infoMemberStates()

    // when
    leader ! ClientMessage(client.ref, AppendWord("I"))       // 0
    leader ! ClientMessage(client.ref, AppendWord("like"))    // 1
    leader ! ClientMessage(client.ref, AppendWord("bananas")) // 2
    leader ! ClientMessage(client.ref, GetWords)              // 3, will compact!
    leader ! ClientMessage(client.ref, GetWords)              // 4, will compact!
    leader ! ClientMessage(client.ref, GetWords)              // 5, will compact!

    Thread.sleep(2000)

    // then
    client.expectMsg(timeout, "I")
    client.expectMsg(timeout, "like")
    client.expectMsg(timeout, "bananas")

    // compacting should not break the state, it stays the same
    client.expectMsg(timeout, List("I", "like", "bananas"))
    client.expectMsg(timeout, List("I", "like", "bananas"))
    client.expectMsg(timeout, List("I", "like", "bananas"))

    eventually {
      val log = leader().underlyingActor.replicatedLog

      log.entries should have length 1 // compaction actually worked
    }
  }

  it should "allow taking an InstallSnapshot from a leader" in {
    // given
    val meta = RaftSnapshotMetadata(Term(2), 6, leader().underlyingActor.stateData.config)
    val snapshotWords = Vector(
      "I",       // 0
      "like",    // 1
      "bananas", // 2
      ", ",      // 3
      "and",     // 4
      "apples",  // 5
      "!"        // 6
    )
    val install = InstallSnapshot(RaftSnapshot(meta, snapshotWords))

    // when
    val follower = followers().head
    info(s"Acting as if Leader is sending InstallSnapshot to ${simpleName(follower)}")
    follower.tell(install, leader())  // we simulate the Leader writing an InstallSnapshot to this follower

    // then
    eventually {
      follower.underlyingActor.words should equal (snapshotWords)
    }

    info("Follower took InstallSnapshot and applied to internal state machine")
  }

}