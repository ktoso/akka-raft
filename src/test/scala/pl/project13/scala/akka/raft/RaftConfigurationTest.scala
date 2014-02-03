package pl.project13.scala.akka.raft

import org.scalatest.{Matchers, FlatSpec}
import org.scalatest.mock.MockitoSugar
import akka.actor.ActorRef

class RaftConfigurationTest extends FlatSpec with Matchers
  with MockitoSugar {

  behavior of "RaftConfiguration"

  it should "should return all members (old / new) during transitioning to new config" in {
    // given
    val ref1 = mock[ActorRef]
    val ref2 = mock[ActorRef]
    val ref3 = mock[ActorRef]

    val config1 = RaftConfiguration(Set(ref1, ref2))
    val config2 = RaftConfiguration(Set(ref2, ref3))

    // when
    val members = config1.transitionTo(config2).members

    // then
    members should equal (Set(ref1, ref2, ref3))
  }

  it should "should return only new members when transitioning finished" in {
    // given
    val ref1 = mock[ActorRef]
    val ref2 = mock[ActorRef]
    val ref3 = mock[ActorRef]

    val config1 = RaftConfiguration(Set(ref1, ref2))
    val config2 = RaftConfiguration(Set(ref2, ref3))

    // when
    val members = config1.transitionTo(config2).transitionToStable.members

    // then
    members should equal (Set(ref2))
  }

  it should "determine if given node will be still available in new config after transition" in {
    // given
    val ref1 = mock[ActorRef]
    val ref2 = mock[ActorRef]
    val ref3 = mock[ActorRef]

    val config1 = RaftConfiguration(Set(ref1, ref2))
    val config2 = RaftConfiguration(Set(ref2, ref3))

    // when
    val members = config1.transitionTo(config2).transitionToStable.members

    // then
    members should equal (Set(ref2, ref3))
  }

  it should "always return true when ased if node will be available in new configuration, when no transition is running" in {
    // given
    val ref1 = mock[ActorRef]
    val ref2 = mock[ActorRef]
    val ref3 = mock[ActorRef]

    val config = RaftConfiguration(Set(ref1, ref2))

    // when
    config.isPartOfNewConfiguration(ref1) should equal (true)
    config.isPartOfNewConfiguration(ref2) should equal (true)
    config.isPartOfNewConfiguration(ref3) should equal (false)
  }

}
