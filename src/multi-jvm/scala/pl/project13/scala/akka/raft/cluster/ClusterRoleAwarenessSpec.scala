package pl.project13.scala.akka.raft.cluster

import akka.testkit.ImplicitSender
import concurrent.duration._
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberUp}
import akka.cluster.Cluster
import akka.actor.Props
import akka.util.Timeout
import clusters._
import pl.project13.scala.akka.raft.example.WordConcatRaftActor

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
        val raftActor = system.actorOf(Props[WordConcatRaftActor], s"raft-$idx")
        system.actorOf(ClusterRaftActor.props(raftActor, initialParticipants), s"raft-member-$idx")
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

class ClusterRoleAwarenessJvmNode1 extends ClusterRoleAwarenessSpec
class ClusterRoleAwarenessJvmNode2 extends ClusterRoleAwarenessSpec
class ClusterRoleAwarenessJvmNode3 extends ClusterRoleAwarenessSpec
class ClusterRoleAwarenessJvmNode4 extends ClusterRoleAwarenessSpec
