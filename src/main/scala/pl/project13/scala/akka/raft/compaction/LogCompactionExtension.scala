package pl.project13.scala.akka.raft.compaction

import akka.actor.{ExtensionIdProvider, ExtendedActorSystem, ExtensionId}

object LogCompactionExtension extends ExtensionId[LogCompactionSupport] with ExtensionIdProvider {

  val ImplKey = "akka.raft.log-compaction-impl"

  def createExtension(system: ExtendedActorSystem) = {
    val config = system.settings.config

    val implClass = config.getString(ImplKey)

    getImpl(system, implClass)
  }

  private def getImpl(system: ExtendedActorSystem, name: String) = {
    try {
      val clazz = getClass.getClassLoader.loadClass(name)

      system.log.debug("Using {} as Raft LogCompactionSupport implementation", clazz)
      clazz.getConstructors()(0).newInstance(system).asInstanceOf[LogCompactionSupport]
    } catch {
      case ex: ClassCastException =>
        throw new IllegalArgumentException(s"Provided log compaction implementation ($name), does not implement ${classOf[LogCompactionSupport].getCanonicalName}!", ex)

      case ex: Exception =>
        throw new IllegalArgumentException(s"Unable to use log compaction implementation: $name", ex)
    }
  }

  def lookup() = LogCompactionExtension
}
