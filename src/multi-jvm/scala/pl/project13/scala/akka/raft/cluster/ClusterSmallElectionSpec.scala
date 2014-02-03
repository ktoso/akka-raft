//package pl.project13.scala.akka.raft.cluster
//
//import akka.remote.testkit.{MultiNodeSpec, MultiNodeConfig}
//import com.typesafe.config.ConfigFactory
//import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
//import akka.testkit.ImplicitSender
//import concurrent.duration._
//import akka.cluster.ClusterEvent.{CurrentClusterState, MemberUp}
//import akka.cluster.Cluster
//import akka.actor.{RootActorPath, Props}
//import pl.project13.scala.akka.raft.example.cluster.WordConcatClusterRaftActor
//import scala.concurrent.Await
//import pl.project13.scala.akka.raft.cluster.ClusterProtocol.{IAmInState, AskForState}
//import akka.pattern.ask
//import akka.util.Timeout
//import clusters._
//
//abstract class SmallClusterElectionSpec extends RaftClusterSpec(ThreeNodesCluster)
//  with ImplicitSender {
//
//  implicit val AskTimeout = {
//    import concurrent.duration._
//    Timeout(3.seconds)
//  }
//
//  def initialParticipants = 3
//
//  behavior of s"Leader election on cluster of $initialParticipants nodes"
//
//  import FiveNodesCluster._
//
//  it should "elect a leader" in within(20.seconds) {
//    Cluster(system).subscribe(testActor, classOf[MemberUp])
//    expectMsgClass(classOf[CurrentClusterState])
//
//    val firstAddress = node(first).address
//    val secondAddress = node(second).address
//    val thirdAddress = node(third).address
//
//    Cluster(system) join firstAddress
//
//    (1 to initialParticipants) map { idx =>
//      runOn(nodes(idx)) {
//        system.actorOf(Props[WordConcatClusterRaftActor], s"member-$idx")
//      }
//    }
//
//    receiveN(3).collect { case MemberUp(m) => m.address }.toSet should be(
//      Set(firstAddress, secondAddress, thirdAddress)
//    )
//
//    Cluster(system).unsubscribe(testActor)
//
//    testConductor.enter("all-up")
//
//    val member1 = Await.result(system.actorSelection(RootActorPath(firstAddress) / "user" / "member-*").resolveOne(1.second), 1.second)
//    val member2 = Await.result(system.actorSelection(RootActorPath(secondAddress) / "user" / "member-*").resolveOne(1.second), 1.second)
//    val member3 = Await.result(system.actorSelection(RootActorPath(thirdAddress) / "user" / "member-*").resolveOne(1.second), 1.second)
//
//    // give raft a bit of time to discover nodes and elect a leader
//    Thread.sleep(1000)
//
//    val state1 = askMemberForState(member1)
//    val state2 = askMemberForState(member2)
//    val state3 = askMemberForState(member3)
//
//    val states = state1 :: state2 :: state3 :: Nil
//    info("Cluster state:")
//    info(s"member-1 is ${state1.state}")
//    info(s"member-2 is ${state2.state}")
//    info(s"member-3 is ${state3.state}")
//
//    states.count(_.state == Leader) should equal (1)
//    states.count(_.state == Candidate) should equal (0)
//    states.count(_.state == Follower) should equal (2)
//  }
//
//}
//
//class SmallClusterElectionSpecMultiJvmNode1 extends SmallClusterElectionSpec
//class SmallClusterElectionSpecMultiJvmNode2 extends SmallClusterElectionSpec
//class SmallClusterElectionSpecMultiJvmNode3 extends SmallClusterElectionSpec