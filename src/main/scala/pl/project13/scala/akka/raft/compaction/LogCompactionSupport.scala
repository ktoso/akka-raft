package pl.project13.scala.akka.raft.compaction

import pl.project13.scala.akka.raft.model.{RaftSnapshot, ReplicatedLog}
import akka.actor.Extension

/**
 * Log Compaction API
 * Thought to be useful for implementing via different snapshot stores (see akka-persistence).
 */
// todo rethink if this design makes sense
// todo implement the default log compacter as akka-persistence snapshot store user
trait LogCompactionSupport extends Extension {

  /**
   * Applies the compaction to the given [[pl.project13.scala.akka.raft.model.ReplicatedLog]].
   * The log's entries up until `meta.lastIncludedIndex` will be replaced with an [[pl.project13.scala.akka.raft.model.SnapshotEntry]].
   *
   * @return the compacted log, guaranteed to not change any items after the snapshot term/index, entries previous to the snapshot will be dropped.
   */
   def compact[Command](replicatedLog: ReplicatedLog[Command], snapshot: RaftSnapshot): ReplicatedLog[Command]

 }
