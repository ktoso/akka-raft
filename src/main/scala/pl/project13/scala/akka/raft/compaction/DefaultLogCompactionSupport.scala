package pl.project13.scala.akka.raft.compaction

import akka.actor.{ActorSystem, Extension}
import pl.project13.scala.akka.raft.model._
import akka.serialization.SerializationExtension
import pl.project13.scala.akka.raft.model.RaftSnapshot

/**
 * Simplest possible log compaction.
 * We do not store snapshots anywhere externaly, just apply the compaction to the replicated log.
 */
class DefaultLogCompactionSupport(system: ActorSystem) extends LogCompactionSupport with Extension {

  val log = system.log
  val serialization = SerializationExtension(system)

  def compact[Command](replicatedLog: ReplicatedLog[Command], snapshot: RaftSnapshot): ReplicatedLog[Command] = {
    log.debug("Compacting replicated log until: {}", snapshot.meta)

    replicatedLog.compactedWith(snapshot)
  }
}
