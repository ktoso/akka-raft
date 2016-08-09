package pl.project13.scala.akka.raft

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.persistence.fsm.PersistentFSM.{CurrentState, SubscribeTransitionCallBack, Transition, UnsubscribeTransitionCallBack}
import pl.project13.scala.akka.raft.StateTransitionMonitoringActor._
import pl.project13.scala.akka.raft.protocol._

import scala.collection._

object StateTransitionMonitoringActor {
  case class Subscribe(members: Seq[ActorRef])
  case class RemoveMember(member: ActorRef)
  case class AddMember(member: ActorRef)
  case object GetLeaders
  case class Leaders(leaders: Seq[ActorRef])
  case object GetCandidates
  case class Candidates(candidates: Seq[ActorRef])
  case object GetFollowers
  case class Followers(followers: Seq[ActorRef])
  case object Unsubscribe
}

/**
 * Actor used to subscribe to fsm state changes in a cluster. Can be used in tests to get nodes that are followers, 
 * candidates and leaders.
 * @param members cluster members 
 */
class StateTransitionMonitoringActor extends Actor with ActorLogging {

  var memberStates = mutable.Map.empty[ActorRef, RaftState]

  var members = Seq.empty[ActorRef]

  override def receive: Receive = {
    case Subscribe(clusterMembers) =>
      members = clusterMembers
      memberStates = mutable.Map.empty[ActorRef, RaftState]
      members.foreach { member =>
      member ! SubscribeTransitionCallBack(self)
    }
    case msg: CurrentState[RaftState] => memberStates += (msg.fsmRef -> msg.state)
    case msg: Transition[RaftState] => memberStates += (msg.fsmRef -> msg.to)
    case RemoveMember(ref) =>
      if(memberStates.contains(ref)) {
        ref ! UnsubscribeTransitionCallBack(self)
        memberStates -= ref
      }
    case AddMember(ref) =>
      ref ! SubscribeTransitionCallBack(self)
      memberStates += (ref -> Init)

    case GetLeaders => sender ! Leaders(memberStates.filter(_._2 == Leader).keySet.toList)
    case GetCandidates => sender ! Candidates(memberStates.filter(_._2 == Candidate).keySet.toList)
    case GetFollowers => sender ! Followers(memberStates.filter(_._2 == Follower).keySet.toList)
    case Unsubscribe =>
      members.foreach { member =>
        member ! UnsubscribeTransitionCallBack(self)
      }
  }
}
