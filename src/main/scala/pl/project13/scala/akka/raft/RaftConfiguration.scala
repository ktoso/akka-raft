package pl.project13.scala.akka.raft

import com.typesafe.config.Config
import java.util.concurrent.TimeUnit
import concurrent.duration._

class RaftConfiguration private (config: Config) {

  val defaultAppendEntriesBatchSize = config.getInt("default-append-entries-batch-size")

  val publishTestingEvents = config.getBoolean("publish-testing-events")

  val electionTimeoutMin = config.getDuration("election-timeout.min", TimeUnit.MILLISECONDS).millis
  val electionTimeoutMax = config.getDuration("election-timeout.max", TimeUnit.MILLISECONDS).millis

  val heartbeatInterval = config.getDuration("heartbeat-interval", TimeUnit.MILLISECONDS).millis
}

object RaftConfiguration {
  def apply(config: Config): RaftConfiguration = {
    new RaftConfiguration(config.getConfig("akka.raft"))
  }
}