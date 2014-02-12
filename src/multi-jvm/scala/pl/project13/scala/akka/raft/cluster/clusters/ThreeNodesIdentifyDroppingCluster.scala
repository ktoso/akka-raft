package pl.project13.scala.akka.raft.cluster.clusters

import akka.remote.testkit.MultiNodeConfig
import com.typesafe.config.{Config, ConfigFactory}
import akka.dispatch._
import java.util.concurrent.ConcurrentLinkedQueue
import akka.actor.{Identify, ActorSystem, ActorRef}
import pl.project13.scala.akka.raft.cluster.ClusterRaftActor

object ThreeNodesIdentifyDroppingCluster extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  val nodes = Map (
    1 -> first,
    2 -> second,
    3 -> third
  )

  val dropEverySecondIdentifyConfig =
    s"""
      |fail-every-second-identify-dispatcher {
      |  mailbox-requirement = "${classOf[DropEverySecondIdentifyMessageQueue].getCanonicalName}"
      |}
      |
      |akka.actor.mailbox.requirements {
      |  "${classOf[DropEverySecondIdentifyMessageQueue].getCanonicalName}" = fail-every-second-identify-dispatcher-mailbox
      |}
      |
      |fail-every-second-identify-dispatcher-mailbox {
      |  mailbox-type = "${classOf[DropFirstIdentifyMessageMailbox].getCanonicalName}"
      |}
    """.stripMargin

  commonConfig(
    ConfigFactory.parseString(dropEverySecondIdentifyConfig).withFallback(
      ConfigFactory.parseResources("cluster.conf").withFallback(
        ConfigFactory.load()
      )
    )
  )
}


// mailbox impl

class DropEverySecondIdentifyMessageQueue extends MessageQueue {

  private final val queue = new ConcurrentLinkedQueue[Envelope]()

  var identifyMsgs = 0

  def enqueue(receiver: ActorRef, handle: Envelope): Unit = handle match {
    case envelope: Envelope if envelope.message.isInstanceOf[Identify] =>
      identifyMsgs += 1
      if (identifyMsgs % 2 == 1) {
        // drop message
        println(s"Dropping msg = ${envelope.message}, from sender = ${envelope.sender}")
      } else {
        println(s"Enqueue msg = ${envelope.message}, from sender = ${envelope.sender}")
        queue.offer(envelope)
      }

    case _ => queue.offer(handle)
  }

  def dequeue(): Envelope = queue.poll()

  def numberOfMessages: Int = queue.size

  def hasMessages: Boolean = !queue.isEmpty

  def cleanUp(owner: ActorRef, deadLetters: MessageQueue) {
    while (hasMessages) {
      deadLetters.enqueue(owner, dequeue())
    }
  }
}

class DropFirstIdentifyMessageMailbox extends MailboxType
  with ProducesMessageQueue[DropEverySecondIdentifyMessageQueue] {

  // This constructor signature must exist, it will be called by Akka
  def this(settings: ActorSystem.Settings, config: Config) =
    this()

  final override def create(owner: Option[ActorRef], system: Option[ActorSystem]): MessageQueue =
    new DropEverySecondIdentifyMessageQueue()
}

class OnFlakyClusterRaftActor(raftMember: ActorRef, waitUntilMembers: Int) extends ClusterRaftActor(raftMember, waitUntilMembers)
  with RequiresMessageQueue[DropEverySecondIdentifyMessageQueue]
