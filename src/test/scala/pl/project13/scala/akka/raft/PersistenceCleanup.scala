package pl.project13.scala.akka.raft

import java.io.File

import akka.testkit.TestKit
import org.iq80.leveldb.util.FileUtils


trait PersistenceCleanup {
  this: TestKit =>

  val journalLocations = List(
    "akka.persistence.journal.leveldb.dir",
    "akka.persistence.snapshot-store.local.dir"
  ) map(s => new File(system.settings.config.getString(s)))

  def persistenceCleanup() = journalLocations.foreach { dir =>
    if (dir.exists()) FileUtils.deleteDirectoryContents(dir)
  }
}
