package pl.project13.scala.akka.raft.cluster

import akka.testkit.ImplicitSender
import concurrent.duration._
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberUp}
import akka.cluster.Cluster
import akka.actor.{RootActorPath, Props}
import akka.util.Timeout
import clusters._
import pl.project13.scala.akka.raft.protocol._
import pl.project13.scala.akka.raft.{RaftClientActor, ClusterConfiguration}
import pl.project13.scala.akka.raft.example.WordConcatRaftActor
import org.scalatest.time.{Millis, Span, Seconds}
import pl.project13.scala.akka.raft.example.protocol._

abstract class ClusterRaftClientSpec extends RaftClusterSpec(ThreeNodesCluster)
  with ImplicitSender {

  implicit val defaultTimeout = {
    import concurrent.duration._
    Timeout(5.seconds)
  }

  override implicit val patienceConfig =
    PatienceConfig(
      timeout = scaled(Span(2, Seconds)),
      interval = scaled(Span(1, Millis))
    )

  def initialParticipants = 3

  behavior of s"${classOf[RaftClientActor].getSimpleName}"

  import ThreeNodesCluster._

  it should "interact with cluster raft actors" in within(20.seconds) {
    Cluster(system).subscribe(testActor, classOf[MemberUp])
    expectMsgClass(classOf[CurrentClusterState])

    val firstAddress = node(first).address
    val secondAddress = node(second).address
    val thirdAddress = node(third).address

    Cluster(system) join firstAddress

    (1 to initialParticipants) map { idx =>
      runOn(nodes(idx)) {
        val raftActor = system.actorOf(Props[WordConcatRaftActor], s"impl-raft-member-$idx")
        system.actorOf(ClusterRaftActor.props(raftActor, initialParticipants), s"raft-member-$idx")
      }
    }

    receiveN(3).collect { case MemberUp(m) => m.address }.toSet should be(
      Set(firstAddress, secondAddress, thirdAddress)
    )

    Cluster(system).unsubscribe(testActor)

    testConductor.enter("all-nodes-up")

    val member1 = selectActorRef(firstAddress, 1)
    val member2 = selectActorRef(secondAddress, 2)
    val member3 = selectActorRef(thirdAddress, 3)
    val members = member1 :: member2 :: member3 :: Nil

    awaitLeaderElected(members)

    testConductor.enter("raft-up")

    // interact with cluster from each node
    runOn(second) {
      val client = system.actorOf(RaftClientActor.props(
        RootActorPath(firstAddress) / "user" / "raft-member-*",
        RootActorPath(secondAddress) / "user" / "raft-member-*",
        RootActorPath(thirdAddress) / "user" / "raft-member-*"
      ), "raft-client")

      client ! AppendWord("I")
      client ! AppendWord("like")
      client ! AppendWord("tea")
      client ! GetWords

      expectMsg("I")
      expectMsg("like")
      expectMsg("tea")
      expectMsg(List("I", "like", "tea"))
    }

    testConductor.enter("client-done")
  }

}

class ClusterRaftClientSpecMultiJvmNode1 extends ClusterRaftClientSpec
class ClusterRaftClientSpecMultiJvmNode2 extends ClusterRaftClientSpec
class ClusterRaftClientSpecMultiJvmNode3 extends ClusterRaftClientSpec