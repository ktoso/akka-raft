package pl.project13.scala.akka.raft.cluster

import pl.project13.scala.akka.raft._
import com.typesafe.config.ConfigFactory
import akka.testkit.TestFSMRef
import pl.project13.scala.akka.raft.example.WordConcatRaftActor

class ClusterRaftSpec(callingThreadDispatcher: Boolean = true) extends RaftSpec(callingThreadDispatcher) {

  import protocol._

  override val config =
    ConfigFactory.parseResources("cluster.conf")
      .withFallback(ConfigFactory.load())

  override val memberCount = 3

  override def createActor(i: Int): TestFSMRef[RaftState, Metadata, WordConcatRaftActor] = {
    ???
  }
}
