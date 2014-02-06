package pl.project13.scala.akka.raft.model

import pl.project13.scala.akka.raft.ClusterConfiguration

case class RaftSnapshot(meta: RaftSnapshotMetadata, data: Any) {

  // todo quite hacky... invent a way to nicely store different things in log
  def toEntry[T] =
    (new Entry(this, meta.lastIncludedTerm, meta.lastIncludedIndex) with SnapshotEntry).asInstanceOf[Entry[T]]

  def toEntrySingleList[T] = List(
    (new Entry(this, meta.lastIncludedTerm, meta.lastIncludedIndex) with SnapshotEntry).asInstanceOf[Entry[T]]
  )
}

/** Metadata describing a raft's state machine snapshot */
case class RaftSnapshotMetadata(lastIncludedTerm: Term, lastIncludedIndex: Int, clusterConfiguration: ClusterConfiguration)
