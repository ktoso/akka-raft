package pl.project13.scala.akka.raft.cluster

import akka.remote.testkit.{MultiNodeSpec, MultiNodeConfig}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import akka.testkit.ImplicitSender
import concurrent.duration._
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberUp}
import akka.cluster.Cluster
import akka.actor.Props
import pl.project13.scala.akka.raft.example.cluster.WordConcatClusterRaftActor
import pl.project13.scala.akka.raft.example.AppendWord
import scala.concurrent.Await
import pl.project13.scala.akka.raft.protocol.InternalProtocol.{IAmInState, AskForState}
import pl.project13.scala.akka.raft.protocol.RaftStates
import pl.project13.scala.akka.raft.protocol.RaftStates.Leader

object RaftClusterConfig extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  val nodes = first :: second :: third :: Nil
  
  commonConfig(
    ConfigFactory.parseResources("cluster.conf")
      .withFallback(ConfigFactory.load())
  )
}

abstract class ClusterElectionSpec extends MultiNodeSpec(RaftClusterConfig)
  with FlatSpecLike with Matchers with BeforeAndAfterAll
  with ImplicitSender {

  def initialParticipants = 3

  behavior of s"Leader election on cluster of $initialParticipants nodes"

  import RaftClusterConfig._

  it should "elect a leader" in within(20.seconds) {
    Cluster(system).subscribe(testActor, classOf[MemberUp])
    expectMsgClass(classOf[CurrentClusterState])

    val firstAddress = node(first).address
    val secondAddress = node(second).address
    val thirdAddress = node(third).address

    Cluster(system) join firstAddress

    (0 until initialParticipants) map { idx =>
      runOn(nodes(idx)) {
        system.actorOf(Props[WordConcatClusterRaftActor], s"member-$idx")
      }
    }

    receiveN(3).collect { case MemberUp(m) => m.address }.toSet should be(
      Set(firstAddress, secondAddress, thirdAddress)
    )

    Cluster(system).unsubscribe(testActor)

    testConductor.enter("all-up")

    val member1 = Await.result(system.actorSelection("/user/member-1").resolveOne(1.second), atMost = 1.second)
    val member2 = Await.result(system.actorSelection("/user/member-2").resolveOne(1.second), atMost = 1.second)
    val member3 = Await.result(system.actorSelection("/user/member-3").resolveOne(1.second), atMost = 1.second)

    Thread.sleep(5000)

    member1 ! AskForState
    member2 ! AskForState
    member3 ! AskForState

    fishForMessage(5.seconds) {
      case IAmInState(Leader) =>
        info("Leader was elected!")
        true
      case other =>
        info("Got message: " + other)
        false
    }

  }

}

class ClusterElectionSpecMultiJvmNode1 extends ClusterElectionSpec
class ClusterElectionSpecMultiJvmNode2 extends ClusterElectionSpec
class ClusterElectionSpecMultiJvmNode3 extends ClusterElectionSpec