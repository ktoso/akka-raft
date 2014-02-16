package pl.project13.scala.akka.raft

import org.scalatest.time.{Seconds, Millis, Span}
import pl.project13.scala.akka.raft.example.protocol._

class RaftClientActorTest extends RaftSpec(callingThreadDispatcher = false) {

  behavior of "RaftClientActor"

  override implicit val patienceConfig = PatienceConfig(timeout = scaled(Span(2, Seconds)), interval = scaled(Span(100, Millis)))

  val initialMembers = 3

  it should "find out Leader of cluster, when pointed to any member of it (already elected leader)" in {
    // given
    subscribeElectedLeader()
    awaitElectedLeader()
    info("Cluster state when joining raft-client: ")
    infoMemberStates()

    val client = system.actorOf(RaftClientActor.props(testRaftMembersPath), "raft-client")

    client ! AppendWord("I")
    client ! AppendWord("like")
    client ! AppendWord("tea")
    client ! GetWords

    // then
    expectMsg("I")
    expectMsg("like")
    expectMsg("tea")
    expectMsg(List("I", "like", "tea"))
  }

}
