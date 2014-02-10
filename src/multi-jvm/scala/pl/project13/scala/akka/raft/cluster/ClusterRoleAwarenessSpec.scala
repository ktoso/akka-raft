package pl.project13.scala.akka.raft.cluster

import akka.testkit.ImplicitSender
import concurrent.duration._
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberUp}
import akka.cluster.Cluster
import akka.actor.Props
import akka.util.Timeout
import pl.project13.scala.akka.raft.example.cluster.WordConcatClusterRaftActor
import clusters._

abstract class ClusterRoleAwarenessSpec extends RaftClusterSpec(FourNodesOnlyTwoRaftNodesCluster)
  with ImplicitSender {

  implicit val defaultTimeout = {
    import concurrent.duration._
    Timeout(3.seconds)
  }

  import FourNodesOnlyTwoRaftNodesCluster._

  def initialParticipants = nodes.size

  behavior of s"Leader election on cluster of $initialParticipants nodes"


  it should "not allow raft Members to be started on Nodes without the 'raft' role" in within(20.seconds) {
    Cluster(system).subscribe(testActor, classOf[MemberUp])
    expectMsgClass(classOf[CurrentClusterState])

    Cluster(system) join node(first).address

    (1 to initialParticipants) map { idx =>
      runOn(nodes(idx)) {
        system.actorOf(Props[WordConcatClusterRaftActor], s"member-$idx")
      }
    }

    Cluster(system).unsubscribe(testActor)

    testConductor.enter("all-nodes-up")

    raftNodes foreach { nonRaftNode =>
      selectActorRefMaybe(node(nonRaftNode).address) should be ('defined)
    }

    nonRaftNodes foreach { nonRaftNode =>
      selectActorRefMaybe(node(nonRaftNode).address) should be ('empty)
    }

  }

}

class ClusterRoleAwarenessSpecMultiJvmNode1 extends ClusterRoleAwarenessSpec
class ClusterRoleAwarenessSpecMultiJvmNode2 extends ClusterRoleAwarenessSpec
class ClusterRoleAwarenessSpecMultiJvmNode3 extends ClusterRoleAwarenessSpec
class ClusterRoleAwarenessSpecMultiJvmNode4 extends ClusterRoleAwarenessSpec
