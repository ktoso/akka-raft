package pl.project13.scala.akka.raft.cluster

import org.scalatest.{Matchers, FlatSpec}
import pl.project13.scala.akka.raft.RaftSpec
import akka.actor.{ActorSystem, Kill}
import com.typesafe.config.ConfigFactory

class ClusterRaftActorTest(_system: ActorSystem) extends RaftSpec(false, Some(_system)) {

  def initialMembers: Int = 1

  def this() {
    this(ActorSystem.apply("rem-syst", ConfigFactory.parseResources("cluster.conf").withFallback(ConfigFactory.load())))
  }

  behavior of "ClusterRaftActor"

  it should "stop when the watched raftActor dies" in {
    // given
    val member = members().head
    val clusterActor = system.actorOf(ClusterRaftActor.props(member, 1))

    probe watch clusterActor

    // when
    member ! Kill

    // then
    probe.expectTerminated(clusterActor)
  }
}
