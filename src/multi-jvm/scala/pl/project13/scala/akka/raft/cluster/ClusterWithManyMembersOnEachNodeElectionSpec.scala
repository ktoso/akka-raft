package pl.project13.scala.akka.raft.cluster

import akka.testkit.ImplicitSender
import concurrent.duration._
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberUp}
import akka.cluster.Cluster
import akka.actor.Props
import pl.project13.scala.akka.raft.example.cluster.WordConcatClusterRaftActor
import akka.util.Timeout
import clusters._

abstract class ClusterWithManyMembersOnEachNodeElectionSpec extends RaftClusterSpec(ThreeNodesCluster)
  with ImplicitSender {

  implicit val AskTimeout = {
    import concurrent.duration._
    Timeout(3.seconds)
  }

  def initialParticipants = 3

  behavior of s"Leader election on cluster of $initialParticipants nodes"

  import ThreeNodesCluster._

  it should "elect a leader, from 5 members, on 3 nodes" in within(20.seconds) {
    Cluster(system).subscribe(testActor, classOf[MemberUp])
    expectMsgClass(classOf[CurrentClusterState])

    val firstAddress = node(first).address
    val secondAddress = node(second).address
    val thirdAddress = node(third).address

    Cluster(system) join firstAddress

    (1 to initialParticipants) map { idx =>
      runOn(nodes(idx)) {
        system.actorOf(Props[WordConcatClusterRaftActor], s"member-$idx")
      }
    }

    // start additional members
    runOn(first) {
      system.actorOf(Props[WordConcatClusterRaftActor], "member-4")
    }
    runOn(second) {
      system.actorOf(Props[WordConcatClusterRaftActor], "member-5")
    }

    receiveN(3).collect { case MemberUp(m) => m.address }.toSet should be(
      Set(firstAddress, secondAddress, thirdAddress)
    )

    Cluster(system).unsubscribe(testActor)

    testConductor.enter("all-up")

    val member1 = selectActorRef(firstAddress,  1)
    val member2 = selectActorRef(secondAddress, 2)
    val member3 = selectActorRef(thirdAddress,  3)
    val member4 = selectActorRef(firstAddress,  4)
    val member5 = selectActorRef(secondAddress, 5)

    // give raft a bit of time to discover nodes and elect a leader
    Thread.sleep(1000)

    val memberStates = askMembersForState(member1, member2, member3, member4, member5)
    
    info("Cluster state:")
    memberStates foreach { state =>
      info(s"${state.simpleName} is ${state.state}")
    }

    memberStates.countLeaders should equal (1)
    memberStates.countCandidates should equal (0)
    memberStates.countFollowers should equal (4)
  }

}

class ClusterWithManyMembersOnEachNodeElectionSpecMultiJvmNode1 extends ClusterWithManyMembersOnEachNodeElectionSpec
class ClusterWithManyMembersOnEachNodeElectionSpecMultiJvmNode2 extends ClusterWithManyMembersOnEachNodeElectionSpec
class ClusterWithManyMembersOnEachNodeElectionSpecMultiJvmNode3 extends ClusterWithManyMembersOnEachNodeElectionSpec