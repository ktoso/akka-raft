package pl.project13.scala.akka.raft

import akka.event.Logging.simpleName
import akka.actor._
import akka.pattern.ask
import protocol._
import concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.collection.immutable.{TreeSet, Set}
import pl.project13.scala.akka.raft.config.RaftConfiguration
import akka.event.Logging
import scala.concurrent.Future
import akka.util.Timeout

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
 * @param raftMembersPaths actor path used to select raft cluster members (suffix it with `*` to glob for many actors)
 */
class RaftClientActor(raftMembersPaths: ActorPath*) extends Actor with ActorLogging with Stash {

  val settings = RaftConfiguration(context.system)
  val debug = settings.publishTestingEvents

  implicit val clientDispatcher = context.system.dispatchers.lookup("raft-client-dispatcher")

  protected var members = TreeSet.empty[ActorRef]
  protected var leader: Option[ActorRef] = None

  override def preStart() {
    super.preStart()

    self ! FindLeader(delay = 1000.milliseconds)
  }

  def receive = {

    // leader handling
    case LeaderIs(Some(newLeader), maybeMsg) =>
      log.info("Member {} informed RaftClient that Leader is {} (previously assumed: {})", sender(), newLeader, leader)
      leader = Some(newLeader)
      unstashAll()

    case LeaderIs(None, maybeMsg) =>
      log.info("Member {} thinks there is no Leader currently in the raft cluster", sender())
//      self ! FindLeader


    // leader finding
    case FindLeader(delay) =>
      asyncRefreshMembers()
      randomMember() foreach { _ ! WhoIsTheLeader }

      if (leader.isEmpty)
        context.system.scheduler.scheduleOnce(delay, self, FindLeader(delay))

    case ActorIdentity(_, member) =>
//      log.info("Adding member {} to known members set: {}", member, members)
      member foreach { members += _ }

    // message handling
    case null =>
      log.warning("null sent as message to {}, ignoring!", simpleName(this))
      // ignore...

    case wrapped: ClientMessage[_] =>
      proxyOrStash(wrapped.cmd, wrapped.client)
      
    case msg =>
      proxyOrStash(msg, sender())
  }


  /** @param userActor end-user actor whom for we're proxying this command */
  def proxyOrStash(msg: Any, userActor: AnyRef) {
    log.info("got : " + msg)
    leader match {
      case Some(lead) =>
        if (debug) publishDebug(s"Proxying ${ClientMessage(sender(), msg)}")
        log.info("Proxying message {} from {} to currently assumed leader {}.", msg, sender(), lead)
        lead forward ClientMessage(sender(), msg)

      case _ =>
        if (debug) publishDebug("Stashing $msg, because no leader known")
        log.info("No leader in cluster, stashing client message of type {} from {}. Will re-send once leader elected.", msg.getClass, sender())
        stash()
    }
  }

  private def asyncRefreshMembers() {
    raftMembersPaths foreach { actorPath =>
      log.info("Selecting on {}", actorPath)
      context.system.actorSelection(actorPath) ! Identify()
    }
  }

  @inline private def publishDebug(msg: String) {
    context.system.eventStream.publish(Logging.Debug(simpleName(this), getClass, msg))
  }

  private def randomMember(): Option[ActorRef] =
    if (members.isEmpty) None
    else members.drop(ThreadLocalRandom.current nextInt members.size).headOption

  final case class FindLeader(delay: FiniteDuration) extends Message[Internal]
}

object RaftClientActor {
  def props(raftMembersPaths: ActorPath*) =
    Props(classOf[RaftClientActor], raftMembersPaths)
}