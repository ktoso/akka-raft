package pl.project13.scala.akka.raft

import akka.event.Logging.simpleName
import akka.actor._
import protocol._
import concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.collection.immutable.Set
import scala.concurrent.Future

/**
 * This actor hides the complexity of being a raft client from you (for example the fact of having to talk with the Leader,
 * when performing writes is abstracted away using this actor).
 *
 *
 * TODO
 * https://github.com/ktoso/akka-raft/issues/13
 * https://github.com/ktoso/akka-raft/issues/15
 *
 * Note to self, I think a proxy one will be easier to implement, because we can delay sending msgs, until we get info
 * that a leader was selected, and it's easier to retry sending hm hm... We'll see.
 *
 * @param raftMembersPath actor path used to select raft cluster members
 */
class RaftClientActor(raftMembersPath: ActorPath) extends Actor with ActorLogging with Stash {

  implicit val clientDispatcher = context.system.dispatchers.lookup("raft-client-dispatcher")

  protected var members = Set.empty[ActorRef]
  protected var leader: Option[ActorRef] = None

  override def preStart() {
    super.preStart()

    self ! FindLeader(delay = 50.milliseconds)
  }

  def receive = {

    // leader handling
    case LeaderIs(Some(newLeader)) =>
      log.info("Member {} informed RaftClient that Leader is {} (previously assumed: {})", sender(), newLeader, leader)
      leader = Some(newLeader)
      unstashAll()

    case LeaderIs(None) =>
      log.info("Member {} thinks there is no Leader currently in the raft cluster", sender())
      self ! FindLeader


    // leader finding
    case FindLeader(delay) if leader.isEmpty =>
      asyncRefreshMembers()
      randomMember() foreach askAboutLeader
      context.system.scheduler.scheduleOnce(delay, self, FindLeader(delay))


    case ActorIdentity(_, Some(member)) =>
      log.info("Adding member {} to known members set", member)
      members += member

    // message handling
    case null =>
      log.warning("null sent as message to {}, ignoring!", simpleName(this))
      // ignore...

    case msg =>
      println("msg.getClass = " + msg.getClass)
      leader match {
        case Some(l) =>
          log.info("Proxying message {} from {} to currently assumed leader {}.", msg, sender(), l)
          l.tell(ClientMessage(sender(), msg), sender())
        case _ =>
        log.info("No leader in cluster, stashing client message of type {} from {}. Will re-send once leader elected.", msg.getClass, sender())
        stash()
      }
  }

  private def askAboutLeader(member: ActorRef) {
    member ! WhoIsTheLeader
  }

  private def asyncRefreshMembers() {
    val selection = context.system.actorSelection(raftMembersPath)
    selection ! Identify()
  }

  private def randomMember(): Option[ActorRef] =
    if (members.isEmpty)
      None
    else
      members.drop(random.nextInt(members.size)).headOption

  // for randomly selecting a member to ask about the leader
  private def random = ThreadLocalRandom.current()

  final case class FindLeader(delay: FiniteDuration) extends Message[Internal]
}
