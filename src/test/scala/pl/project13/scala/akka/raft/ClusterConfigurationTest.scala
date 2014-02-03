package pl.project13.scala.akka.raft

import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import akka.actor.ActorSystem
import akka.testkit.{TestProbe, TestKit}

class ClusterConfigurationTest extends TestKit(ActorSystem("config-test"))
  with FlatSpecLike with Matchers
  with BeforeAndAfterAll {

  behavior of "RaftConfiguration"

  override def afterAll() {
    shutdown(system)
  }

  it should "should return all members (old / new) during transitioning to new config" in {
    // given
    val ref1 = TestProbe().ref
    val ref2 = TestProbe().ref
    val ref3 = TestProbe().ref

    val config1 = ClusterConfiguration(Set(ref1, ref2))
    val config2 = ClusterConfiguration(Set(ref2, ref3))

    // when
    val members = config1.transitionTo(config2).members

    // then
    members should equal (Set(ref1, ref2, ref3))
  }

  it should "should return only new members when transitioning finished" in {
    // given
    val ref1 = TestProbe().ref
    val ref2 = TestProbe().ref
    val ref3 = TestProbe().ref

    val config1 = ClusterConfiguration(Set(ref1, ref2))
    val config2 = ClusterConfiguration(Set(ref2, ref3))

    // when
    val members = config1.transitionTo(config2).transitionToStable.members

    // then

    members should equal (Set(ref2, ref3))
  }

  it should "determine if given node will be still available in new config after transition" in {
    // given
    val ref1 = TestProbe().ref
    val ref2 = TestProbe().ref
    val ref3 = TestProbe().ref

    val config1 = ClusterConfiguration(Set(ref1, ref2))
    val config2 = ClusterConfiguration(Set(ref2, ref3))

    // when
    val members = config1.transitionTo(config2).transitionToStable.members

    // then
    members should equal (Set(ref2, ref3))
  }

  it should "always return true when ased if node will be available in new configuration, when no transition is running" in {
    // given
    val ref1 = TestProbe().ref
    val ref2 = TestProbe().ref
    val ref3 = TestProbe().ref

    val config = ClusterConfiguration(Set(ref1, ref2))

    // when
    config.isPartOfNewConfiguration(ref1) should equal (true)
    config.isPartOfNewConfiguration(ref2) should equal (true)
    config.isPartOfNewConfiguration(ref3) should equal (false)
  }

}
