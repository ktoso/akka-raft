package pl.project13.scala.akka.raft.cluster.clusters

import akka.remote.testkit.MultiNodeConfig
import com.typesafe.config.ConfigFactory

object ThreeNodesCluster extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  val nodes = Map (
    1 -> first,
    2 -> second,
    3 -> third
  )

  commonConfig(
    ConfigFactory.parseResources("cluster.conf")
      .withFallback(ConfigFactory.load())
  )
}