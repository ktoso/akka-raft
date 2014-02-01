package pl.project13.scala.akka.raft.example.cluster

import akka.actor.{Props, ActorSystem}
import com.typesafe.config.ConfigFactory
import akka.cluster.Cluster

object WordConcatClusterApp extends App {
  if (args.nonEmpty) System.setProperty("akka.remote.netty.tcp.port", args(0))

  val config =
    ConfigFactory.parseResources("cluster.conf")
      .withFallback(ConfigFactory.load())

  val system = ActorSystem("RaftSystem", config)

  val member = system.actorOf(Props(classOf[WordConcatClusterRaftActor]))

  Cluster(system).subscribe(member)

}
