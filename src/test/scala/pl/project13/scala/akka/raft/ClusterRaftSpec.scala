package pl.project13.scala.akka.raft

import akka.actor._
import com.typesafe.config.ConfigFactory

/**
 * Base class for tests touching ClusterRaftActor, and clustering in general, but do not need to be multi-jvm yet.
 */
abstract class ClusterRaftSpec(_system: ActorSystem) extends RaftSpec(false, Some(_system)) {
  def this() {
    this(ActorSystem.apply("rem-syst", ConfigFactory.parseResources("cluster.conf").withFallback(ConfigFactory.load())))
  }
}