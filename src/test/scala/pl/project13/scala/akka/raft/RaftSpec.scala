package pl.project13.scala.akka.raft

import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import akka.testkit.{TestFSMRef, TestKit}
import akka.actor.ActorSystem
import pl.project13.scala.akka.raft.protocol._

abstract class RaftSpec extends TestKit(ActorSystem("raft-test")) with FlatSpecLike with Matchers
  with BeforeAndAfterAll {

  import protocol._

  var members: Vector[TestFSMRef[RaftState, Metadata, RaftActor]] = _

  def memberCount: Int

  override def beforeAll() {
    super.beforeAll()
    members = (1 to memberCount).toVector map { i => TestFSMRef(new RaftActor, name = s"member-$i") }
    members foreach { _ ! MembersChanged(members) }
  }

  def infoMemberStates() {
    info(s"Members: ${members.map(_.stateName).mkString(", ")}")
  }

  override def afterAll() {
    super.afterAll()
    shutdown(system)
  }

}
