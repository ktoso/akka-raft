package pl.project13.scala.akka.raft.cluster

import akka.testkit.ImplicitSender
import concurrent.duration._
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberUp}
import akka.cluster.Cluster
import akka.actor.Props
import akka.util.Timeout
import clusters._
import pl.project13.scala.akka.raft.example.WordConcatRaftActor

abstract class ClusterIdentifyOnIdentifyDroppingNetworkSpec extends RaftClusterSpec(ThreeNodesIdentifyDroppingCluster)
  with ImplicitSender {

  implicit val defaultTimeout = {
    import concurrent.duration._
    Timeout(10.seconds)
  }

  import ThreeNodesCluster._

  def initialParticipants = nodes.size

  behavior of s"ClusterRaftActor on fleaky network"

  it should "should retry sending Identify until it get's back raft members from the other node" in within(20.seconds) {
    Cluster(system).subscribe(testActor, classOf[MemberUp])
    expectMsgClass(classOf[CurrentClusterState])

    Cluster(system) join node(first).address

    (1 to initialParticipants) map { idx =>
      runOn(nodes(idx)) {
        val wordsActor = system.actorOf(Props[WordConcatRaftActor], s"words-$idx")
        system.actorOf(Props(classOf[OnFleakyClusterRaftActor], wordsActor), s"member-$idx")
      }
    }

    Cluster(system).unsubscribe(testActor)

    testConductor.enter("all-nodes-up")

    nodes.values foreach { nonRaftNode =>
      selectActorRefMaybe(node(nonRaftNode).address) should be ('defined)
    }

  }

}

class ClusterIdentifyOnIdentifyDroppingNetworkMultiJvmNode1 extends ClusterIdentifyOnIdentifyDroppingNetworkSpec
class ClusterIdentifyOnIdentifyDroppingNetworkMultiJvmNode2 extends ClusterIdentifyOnIdentifyDroppingNetworkSpec
class ClusterIdentifyOnIdentifyDroppingNetworkMultiJvmNode3 extends ClusterIdentifyOnIdentifyDroppingNetworkSpec
