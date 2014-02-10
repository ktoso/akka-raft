package pl.project13.scala.akka.raft.cluster.clusters

import akka.remote.testkit.MultiNodeConfig
import com.typesafe.config.ConfigFactory

object FourNodesOnlyTwoRaftNodesCluster extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  val nodes = Map (
    1 -> first,
    2 -> second,
    3 -> third,
    4 -> fourth
  )

  val raftNodes = List(first, third)
  val nonRaftNodes = List(second, fourth)

  commonConfig(
    ConfigFactory.parseResources("cluster.conf")
      .withFallback(ConfigFactory.load())
  )

  nodeConfig(raftNodes: _*)(
    ConfigFactory.parseResources("cluster.conf")
      .withFallback(ConfigFactory.load())
  )

  nodeConfig(nonRaftNodes: _*)(
    ConfigFactory.parseString(
    """
      |akka {
      |  cluster {
      |    roles = [ "something" ]
      |  }
      |}
    """.stripMargin)
  )
}