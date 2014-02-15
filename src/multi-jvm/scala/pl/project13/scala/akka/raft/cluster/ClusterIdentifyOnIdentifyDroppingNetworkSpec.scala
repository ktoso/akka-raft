package pl.project13.scala.akka.raft.cluster

import akka.testkit.ImplicitSender
import concurrent.duration._
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberUp}
import akka.cluster.Cluster
import akka.actor.Props
import akka.util.Timeout
import clusters._
import pl.project13.scala.akka.raft.example.WordConcatRaftActor
import org.scalatest.time.{Seconds, Span}

/**
* ''What is this test:''
*
* Test to verify that auto discovering RaftActors on remote Nodes still works even if half of the Identify messages are lost.
* Crazy case, but as seen, we retry until we get what we need.
*
* Also, no need for getting initial config sent to all nodes - they auto discover the other nodes thanks to akka-cluster letting us know about
* "new node with 'raft' role just apeared.
*/
abstract class ClusterIdentifyOnIdentifyDroppingNetworkSpec extends RaftClusterSpec(ThreeNodesIdentifyDroppingCluster)
  with ImplicitSender {

  implicit val defaultTimeout = {
    import concurrent.duration._
    Timeout(10.seconds)
  }

  override implicit val patienceConfig =
    PatienceConfig(
      timeout = scaled(Span(30, Seconds)),
      interval = scaled(Span(1, Seconds))
    )

  import ThreeNodesIdentifyDroppingCluster._

  def initialParticipants = nodes.size

  behavior of s"ClusterRaftActor on flaky network"

  it should "should retry sending Identify until it get's back raft members from the other node" in within(1.minute) {
    Cluster(system).subscribe(testActor, classOf[MemberUp])
    expectMsgClass(classOf[CurrentClusterState])

    Cluster(system) join node(first).address

    (1 to initialParticipants) map { idx =>
      runOn(nodes(idx)) {
        val wordsActor = system.actorOf(Props[WordConcatRaftActor], s"flaky-words-$idx")
        system.actorOf(Props(classOf[OnFlakyClusterRaftActor], wordsActor, initialParticipants), s"raft-member-$idx")
      }
    }

    // notice that we don't sent the configuration to the nodes, they find themselfes automatically!

    Cluster(system).unsubscribe(testActor)

    testConductor.enter("all-nodes-up")

    eventually {
      nodes.values foreach { nonRaftNode =>
        selectActorRefMaybe(node(nonRaftNode).address) should be ('defined)
      }
    }

    testConductor.enter("all-members-found")
  }

}

class ClusterIdentifyOnIdentifyDroppingNetworkMultiJvmNode1 extends ClusterIdentifyOnIdentifyDroppingNetworkSpec
class ClusterIdentifyOnIdentifyDroppingNetworkMultiJvmNode2 extends ClusterIdentifyOnIdentifyDroppingNetworkSpec
class ClusterIdentifyOnIdentifyDroppingNetworkMultiJvmNode3 extends ClusterIdentifyOnIdentifyDroppingNetworkSpec
