package pl.project13.scala.akka.raft.cluster

import pl.project13.scala.akka.raft.protocol._
import org.scalatest.{BeforeAndAfterAll, Matchers, FlatSpecLike}
import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec}
import akka.util.Timeout
import akka.actor.{Address, RootActorPath, ActorRef}
import scala.concurrent.{Future, Await}
import akka.pattern.ask
import pl.project13.scala.akka.raft.cluster.ClusterProtocol.{AskForState, IAmInState}

abstract class RaftClusterSpec(config: MultiNodeConfig) extends MultiNodeSpec(config)
  with FlatSpecLike with Matchers with BeforeAndAfterAll {

  import concurrent.duration._
  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val AskTimeout: Timeout
  
  def selectActorRef(nodeAddress: Address, memberNr: Int): ActorRef = {
    val selection = system.actorSelection(RootActorPath(nodeAddress) / "user" / s"member-$memberNr*")
    Await.result(selection.resolveOne(1.second), 1.second)
  }

  def askMemberForState(refs: ActorRef): MemberAndState =
    askMembersForState(refs).head

  def askMembersForState(refs: ActorRef*): List[MemberAndState] = {
    val stateFutures = refs map { ref => (ref ? AskForState).mapTo[IAmInState] }
    val statesFuture = Future.sequence(stateFutures)

    val states = Await.result(statesFuture, atMost = 3.seconds).zip(refs) map { case (state, ref) =>
      MemberAndState(ref, state.state)
    }
    states.toList
  }

  case class MemberAndState(member: ActorRef, state: RaftState) {
    def simpleName = member.path.elements.last
  }

  implicit class MemberCounter(members: List[MemberAndState]) {
    def countFollowers = members.count(_.state == Follower)
    def countCandidates = members.count(_.state == Candidate)
    def countLeaders = members.count(_.state == Leader)
  }
}
