package pl.project13.scala.akka.raft.config

import akka.actor.{ExtendedActorSystem, ExtensionIdProvider, ExtensionId}

object RaftConfiguration extends ExtensionId[RaftConfig] with ExtensionIdProvider {

  def lookup() = RaftConfiguration

  def createExtension(system: ExtendedActorSystem): RaftConfig = new RaftConfig(system.settings.config)
}
