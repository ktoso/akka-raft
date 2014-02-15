package pl.project13.scala.akka.raft.cluster

import pl.project13.scala.akka.raft.protocol._
import org.scalatest.{BeforeAndAfterAll, Matchers, FlatSpecLike}
import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec}
import akka.util.Timeout
import akka.actor.{Address, RootActorPath, ActorRef}
import scala.concurrent.{Future, Await}
import akka.pattern.ask
import org.scalatest.concurrent.Eventually

abstract class RaftClusterSpec(config: MultiNodeConfig) extends MultiNodeSpec(config)
  with Eventually with ClusterPatience
  with FlatSpecLike with Matchers with BeforeAndAfterAll {

  import concurrent.duration._
  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val defaultTimeout: Timeout
  
  def selectActorRefMaybe(nodeAddress: Address): Option[ActorRef] = {
    val selection = system.actorSelection(RootActorPath(nodeAddress) / "user" / s"raft-member-*")
    
    try {
      Some(Await.result(selection.resolveOne(1.second), 1.second))
    } catch {
      case ex: Exception =>
        None
    }
  }
  
  def selectActorRef(nodeAddress: Address, memberNr: Int): ActorRef = {
    val selection = system.actorSelection(RootActorPath(nodeAddress) / "user" / s"raft-member-$memberNr*")
    Await.result(selection.resolveOne(1.second), 1.second)
  }

  // todo can be implemented by listening to remote eventStream: http://doc.akka.io/docs/akka/snapshot/java/remoting.html#remote-events
  def awaitLeaderElected(members: List[ActorRef]) {
    val start = System.currentTimeMillis()
    awaitCond(
      askMembersForState(members).maybeLeader().isDefined,
      defaultTimeout.duration,
      20.millis,
      "Leader election did not succeed happen within given time range!"
    )

    info(s"Waited for Leader election for ${System.currentTimeMillis() - start}ms")
  }

  def askMemberForState(refs: ActorRef): MemberAndState =
    askMembersForState(refs).members.head

  def askMembersForState(refs: List[ActorRef]): MemberCounter =
    askMembersForState(refs: _*)

  def askMembersForState(refs: ActorRef*): MemberCounter = {
    val stateFutures = refs map { ref => (ref ? AskForState).mapTo[IAmInState] }
    val statesFuture = Future.sequence(stateFutures)

    val states = Await.result(statesFuture, atMost = defaultTimeout.duration).zip(refs) map { case (state, ref) =>
      MemberAndState(ref, state.state)
    }

    MemberCounter(states.toList)
  }

  case class MemberAndState(member: ActorRef, state: RaftState) {
    def simpleName = member.path.elements.last
  }

  case class MemberCounter(members: List[MemberAndState]) {
    def followers() = members.filter(_.state.toString == Follower.toString)
    def candidates() = members.filter(_.state.toString == Candidate.toString)
    def leaders() = members.filter(_.state.toString == Leader.toString)

    def leader() = maybeLeader().getOrElse { throw new Exception("Unable to find leader! Members: " + members) }
    def maybeLeader() = {
      val leads = leaders()
      require(leads.size < 2, s"Must have 1 or 0 leaders, yet found ${leads.size}! Members: $members")
      leads.headOption
    }

    def infoMemberStates() {
      info(s"Members: ${members.map(m => s"""${m.simpleName}[${m.state}]""").mkString(", ")}")
    }
  }

  def simpleName(ref: ActorRef): String = ref.path.elements.last
}
