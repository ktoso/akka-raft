package pl.project13.scala.akka.raft.cluster

import akka.actor.Actor
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.ClusterEvent.UnreachableMember
import pl.project13.scala.akka.raft.RaftActor

/**
 * Akka cluster ready [[pl.project13.scala.akka.raft.RaftActor]].
 */
trait ClusterRaftActor extends RaftActor {

  val cluster = Cluster(context.system)

  override def preStart(): Unit =
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])

  override def postStop(): Unit =
    cluster.unsubscribe(self)

  override def aroundReceive(receive: Actor.Receive, msg: Any): Unit = msg match {
    case MemberUp(member) =>
        log.info("Member is Up: {}", member.address)
//        self ! MembersChanged // todo

    case UnreachableMember(member) =>
      log.info("Member detected as unreachable: {}", member)

    case MemberRemoved(member, previousStatus) =>
      log.info("Member is Removed: {} after {}", member.address, previousStatus)

    case _: MemberEvent => // ignore

    case _ =>
      // everything else just push to the RaftActor's receive
      super.aroundReceive(receive, msg)
  }
}
