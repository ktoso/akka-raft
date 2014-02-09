package pl.project13.scala.akka.raft.cluster

import org.scalatest.concurrent.PatienceConfiguration
import concurrent.duration._
import org.scalatest.time._

trait ClusterPatience extends PatienceConfiguration {
  override implicit val patienceConfig =
    PatienceConfig(
      timeout = scaled(Span(5, Seconds)),
      interval = scaled(Span(300, Millis))
    )

}