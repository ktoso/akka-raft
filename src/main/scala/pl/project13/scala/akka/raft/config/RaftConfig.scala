package pl.project13.scala.akka.raft.config

import com.typesafe.config.Config
import java.util.concurrent.TimeUnit
import concurrent.duration._
import akka.actor.Extension

class RaftConfig (config: Config) extends Extension {

  val raftConfig = config.getConfig("akka.raft")

  val defaultAppendEntriesBatchSize = raftConfig.getInt("default-append-entries-batch-size")

  val publishTestingEvents = raftConfig.getBoolean("publish-testing-events")

  val electionTimeoutMin = raftConfig.getDuration("election-timeout.min", TimeUnit.MILLISECONDS).millis
  val electionTimeoutMax = raftConfig.getDuration("election-timeout.max", TimeUnit.MILLISECONDS).millis

  val heartbeatInterval = raftConfig.getDuration("heartbeat-interval", TimeUnit.MILLISECONDS).millis

  val clusterAutoDiscoveryIdentifyTimeout = raftConfig.getDuration("cluster.auto-discovery.identify-timeout", TimeUnit.MILLISECONDS).millis
  val clusterAutoDiscoveryRetryCount = raftConfig.getInt("cluster.auto-discovery.retry-count")
}
