package pl.project13.scala.akka.raft

import akka.testkit.ImplicitSender
import org.scalatest._
import pl.project13.scala.akka.raft.example.protocol._
import pl.project13.scala.akka.raft.protocol._

class LeaderTest extends RaftSpec with FlatSpecLike with Matchers
  with ImplicitSender
  with BeforeAndAfter with BeforeAndAfterAll {

  behavior of "Leader"

  override def initialMembers: Int = 3

  it should "commit an entry once it has been written by the majority of the Followers" in {
    subscribeBeginAsLeader()
    val msg = awaitBeginAsLeader()
    val leader = msg.ref

    leader ! ClientMessage(self, AppendWord("a"))
    leader ! ClientMessage(self, AppendWord("b"))
    leader ! ClientMessage(self, AppendWord("c"))

    subscribeEntryComitted()
    awaitEntryComitted(1)
    awaitEntryComitted(2)
    awaitEntryComitted(3)
  }

  it should "reply with it's current configuration when asked to" in {
    // note: this is used when an actor has died and starts again in Init state

    val leader = leaders.head

    // when
    leader ! RequestConfiguration

    // then
    val initialMembers = members.toSet
    fishForMessage() {
      case ChangeConfiguration(StableClusterConfiguration(0, potentialMembers)) if initialMembers == potentialMembers  => true
      case msg @ _=> false
    }
  }

  override def beforeAll(): Unit =
    subscribeClusterStateTransitions()
    super.beforeAll()
}