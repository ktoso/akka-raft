package pl.project13.scala.akka.raft.cluster

import akka.testkit.ImplicitSender
import concurrent.duration._
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberUp}
import akka.cluster.Cluster
import akka.actor.Props
import akka.util.Timeout
import clusters._
import pl.project13.scala.akka.raft.protocol._
import pl.project13.scala.akka.raft.ClusterConfiguration
import pl.project13.scala.akka.raft.example.WordConcatRaftActor
import org.scalatest.time.{Millis, Span, Seconds}

abstract class SmallClusterElectionSpec extends RaftClusterSpec(ThreeNodesCluster)
  with ImplicitSender {

  implicit val defaultTimeout = {
    import concurrent.duration._
    Timeout(3.seconds)
  }

  override implicit val patienceConfig =
    PatienceConfig(
      timeout = scaled(Span(20, Seconds)),
      interval = scaled(Span(1, Millis))
    )

  def initialParticipants = 3

  behavior of s"Leader election on cluster of $initialParticipants nodes"

  import FiveNodesCluster._

  it should "elect a leader" in within(30.seconds) {
    Cluster(system).subscribe(testActor, classOf[MemberUp])
    expectMsgClass(classOf[CurrentClusterState])

    val firstAddress = node(first).address
    val secondAddress = node(second).address
    val thirdAddress = node(third).address

    Cluster(system) join firstAddress

    (1 to initialParticipants) map { idx =>
      runOn(nodes(idx)) {
        val raftActor = system.actorOf(Props[WordConcatRaftActor], s"small-raft-$idx")
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

    // initialize raft
    val clusterConfig = ClusterConfiguration(members)
    members foreach { _ ! ChangeConfiguration(clusterConfig) }

    testConductor.enter("raft-up")

    eventually {
      val states = askMembersForState(members: _*)

      states.leaders().size should equal (1)
      (states.candidates().size + states.followers().size) should equal (2)
      states.infoMemberStates()
    }


  }

}

class SmallClusterElectionSpecMultiJvmNode1 extends SmallClusterElectionSpec
class SmallClusterElectionSpecMultiJvmNode2 extends SmallClusterElectionSpec
class SmallClusterElectionSpecMultiJvmNode3 extends SmallClusterElectionSpec