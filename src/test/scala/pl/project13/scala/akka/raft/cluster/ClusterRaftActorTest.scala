package pl.project13.scala.akka.raft.cluster

import pl.project13.scala.akka.raft.ClusterRaftSpec
import akka.actor.Kill

class ClusterRaftActorTest extends ClusterRaftSpec {

  def initialMembers: Int = 1

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
