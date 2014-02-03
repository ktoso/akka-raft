package pl.project13.scala.akka.raft.cluster.clusters

import akka.remote.testkit.MultiNodeConfig
import com.typesafe.config.ConfigFactory

object FiveNodesCluster extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")

  val nodes = Map (
    1 -> first,
    2 -> second,
    3 -> third,
    4 -> fourth,
    5 -> fifth
  )

  commonConfig(
    ConfigFactory.parseResources("cluster.conf")
      .withFallback(ConfigFactory.load())
  )
}