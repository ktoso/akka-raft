package pl.project13.scala.akka.raft

import org.scalatest._
import akka.testkit.{ImplicitSender, TestProbe, TestFSMRef, TestKit}
import akka.actor._
import java.util.concurrent.TimeUnit
import akka.fsm.hack.TestFSMRefHack
import pl.project13.scala.akka.raft.example.WordConcatRaftActor
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import scala.concurrent.duration.FiniteDuration

/**
 * @param callingThreadDispatcher if true, will run using one thread. Use this for FSM tests, otherwise set to false to
 *                                enable a "really threading" dispatcher (see config for `raft-dispatcher`).
 */
abstract class RaftSpec(callingThreadDispatcher: Boolean = true, _system: Option[ActorSystem] = None)
  extends TestKit(_system getOrElse ActorSystem("raft-test"))
  with ImplicitSender with Eventually
  with FlatSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  import protocol._

  val DefaultTimeout = 5

  import scala.concurrent.duration._
  val DefaultTimeoutDuration: FiniteDuration = DefaultTimeout.seconds

  override implicit val patienceConfig = PatienceConfig(
    timeout = scaled(Span(DefaultTimeout, Seconds)),
    interval = scaled(Span(200, Millis))
  )
  
  // notice the EventStreamAllMessages, thanks to this we're able to wait for messages like "leader elected" etc.
  protected var _members: Vector[TestFSMRef[RaftState, Metadata, SnapshottingWordConcatRaftActor]] = Vector.empty

  def initialMembers: Int

  lazy val config = system.settings.config
  lazy val electionTimeoutMin = config.getDuration("akka.raft.election-timeout.min", TimeUnit.MILLISECONDS).millis
  lazy val electionTimeoutMax = config.getDuration("akka.raft.election-timeout.max", TimeUnit.MILLISECONDS).millis

  implicit var probe: TestProbe = _

  var raftConfiguration: ClusterConfiguration = _

  override def beforeAll() {
    super.beforeAll()

    (1 to initialMembers).toList foreach { i => createActor(s"raft-member-$i") }

    raftConfiguration = ClusterConfiguration(_members)
    _members foreach { _ ! ChangeConfiguration(raftConfiguration) }
  }


  def testRaftMembersPath = system / "raft-member-*"

  /**
   * Creates an Actor which uses either the CallingThreadDispatcher, or a "real" (raft-dispatcher) with proper parallelism.
   * > Use the "real" one for feature tests, so actors won't block each other in the CallingThread.
   * > Use the CallingThreadDispatcher for FSM tests, such as [[pl.project13.scala.akka.raft.CandidateTest]]
   */
  def createActor(name: String): TestFSMRef[RaftState, Metadata, SnapshottingWordConcatRaftActor] = {
    val actor =
      if (callingThreadDispatcher)
        TestFSMRef(
          (new WordConcatRaftActor with EventStreamAllMessages).asInstanceOf[SnapshottingWordConcatRaftActor], // hack, bleh
          name = name
        )
      else
        TestFSMRefHack[RaftState, Metadata, SnapshottingWordConcatRaftActor](
          Props(new WordConcatRaftActor with EventStreamAllMessages).withDispatcher("raft-dispatcher"),
          name = name
        )

    _members :+= actor
    actor
  }

  override def beforeEach() {
    super.beforeEach()

    probe = TestProbe()
  }

  override def afterEach() {
    system.eventStream.unsubscribe(probe.ref)
    super.afterEach()
  }

  def maybeLeader() = _members.find(_.stateName == Leader)

  def leader() = maybeLeader getOrElse {
    throw new RuntimeException("Unable to find leader! members: " + _members)
  }

  def leaders() =
    members().filter(_.stateName == Leader)

  def members() =
    _members

  def followers() =
    _members.filter(m => m.stateName == Follower && !m.isTerminated)

  def follower(name: String) =
    _members.find(_.path.elements.last == name).get

  def candidates() =
    _members.filter(m => m.stateName == Candidate && !m.isTerminated)

  def simpleName(ref: ActorRef) = {
    import collection.JavaConverters._
    ref.path.getElements.asScala.last
  }

  def infoMemberStates() {
    info(s"Members: ${members().map(m => s"""${simpleName(m)}[${m.stateName}]""").mkString(", ")}")
  }

  // cluster management

  def killLeader() = {
    val leaderToStop = leader()
    _members = _members filterNot { _ == leaderToStop }
    leaderToStop.stop()
    info(s"Killed leader: ${simpleName(leaderToStop)}")
    leaderToStop
  }

  def killMember(member: TestFSMRef[RaftState, Metadata, SnapshottingWordConcatRaftActor]) = {
    system stop member
    _members = _members filterNot { _ == member }
    info(s"Killed member: ${simpleName(member)}")
    Thread.sleep(10)

    member
  }

  def restartMember(member: TestFSMRef[RaftState, Metadata, SnapshottingWordConcatRaftActor]) = {
    createActor(member.path.elements.last)
    info(s"Started member: ${simpleName(member)}")
    Thread.sleep(10)

    member
  }

  // await stuff -------------------------------------------------------------------------------------------------------

  def subscribeElectedLeader()(implicit probe: TestProbe): Unit =
    system.eventStream.subscribe(probe.ref, ElectedAsLeader.getClass)

  def awaitElectedLeader(max: FiniteDuration = DefaultTimeoutDuration)(implicit probe: TestProbe): Unit =
    probe.expectMsgClass(max, ElectedAsLeader.getClass)

  def subscribeBeginElection()(implicit probe: TestProbe): Unit =
    system.eventStream.subscribe(probe.ref, BeginElection.getClass)

  def awaitBeginElection(max: FiniteDuration = DefaultTimeoutDuration)(implicit probe: TestProbe): Unit =
    probe.expectMsgClass(max, BeginElection.getClass)

  def subscribeEntryComitted()(implicit probe: TestProbe): Unit =
    system.eventStream.subscribe(probe.ref, classOf[EntryCommitted])

  def awaitEntryComitted(Index: Int, max: FiniteDuration = DefaultTimeoutDuration)(implicit probe: TestProbe): Unit = {
    val start = System.currentTimeMillis()
    probe.fishForMessage(max, hint = s"EntryCommitted($Index, actor)") {
      case EntryCommitted(Index, actor) =>
        info(s"Finished fishing for EntryCommitted($Index) on ${simpleName(actor)}, took ${System.currentTimeMillis() - start}ms")
        true

      case other =>
        info(s"Fished $other, still waiting for ${EntryCommitted(Index, null)}...")
        false
    }
  }

  // end of await stuff ------------------------------------------------------------------------------------------------

  override def afterAll() {
    super.afterAll()
    shutdown(system)
  }

}
