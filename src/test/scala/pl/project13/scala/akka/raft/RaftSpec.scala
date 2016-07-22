package pl.project13.scala.akka.raft

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit, TestProbe}
import org.scalatest._
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import pl.project13.scala.akka.raft.example.WordConcatRaftActor

import scala.util.Random

abstract class RaftSpec(_system: Option[ActorSystem] = None) extends TestKit(_system getOrElse ActorSystem("raft-test"))
  with ImplicitSender with Eventually with FlatSpecLike with Matchers
  with BeforeAndAfterAll with BeforeAndAfterEach with PersistenceCleanup {

  import pl.project13.scala.akka.raft.protocol._

  val DefaultTimeout = 5

  import scala.concurrent.duration._
  val DefaultTimeoutDuration: FiniteDuration = DefaultTimeout.seconds

  override implicit val patienceConfig = PatienceConfig(
    timeout = scaled(Span(DefaultTimeout, Seconds)),
    interval = scaled(Span(200, Millis))
  )
  
  // notice the EventStreamAllMessages, thanks to this we're able to wait for messages like "leader elected" etc.
  protected var _members: Vector[ActorRef] = Vector.empty

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
  def createActor(name: String): ActorRef = {
    val actor = system.actorOf(Props(new WordConcatRaftActor).withDispatcher("raft-dispatcher"), name)
    _members :+= actor
    actor
  }

  override def beforeEach() {
    super.beforeEach()
    persistenceCleanup()
    probe = TestProbe()
  }

  override def afterEach() {
    system.eventStream.unsubscribe(probe.ref)
    super.afterEach()
  }

  def simpleName(ref: ActorRef) = {
    import scala.collection.JavaConverters._
    ref.path.getElements.asScala.last
  }

  def infoMemberStates() {
    //info(s"Members: ${members().map(m => s"""${simpleName(m)}[${m.stateName}]""").mkString(", ")}")
  }

  def killMember(member: TestFSMRef[RaftState, Metadata, SnapshottingWordConcatRaftActor]) = {
    system stop member
    _members = _members filterNot { _ == member }
    info(s"Killed member: ${simpleName(member)}")
    Thread.sleep(10)

    member
  }

  def restartMember(member: ActorRef = _members(Random.nextInt(_members.size))) = {
    probe.watch(member)
    system.stop(member)
    _members = _members.filterNot(_ == member)
    probe.expectTerminated(member)

    val newMember = createActor(member.path.name)
    newMember
  }

  // await stuff -------------------------------------------------------------------------------------------------------

  def subscribeElectedLeader()(implicit probe: TestProbe): Unit =
    system.eventStream.subscribe(probe.ref, ElectedAsLeader.getClass)

  def awaitElectedLeader(max: FiniteDuration = DefaultTimeoutDuration)(implicit probe: TestProbe): Unit =
    probe.expectMsgClass(max, ElectedAsLeader.getClass)

  def subscribeBeginElection()(implicit probe: TestProbe): Unit =
    //system.eventStream.subscribe(probe.ref, BeginElection.getClass)
    system.eventStream.subscribe(probe.ref, classOf[ElectionStarted])

  def subscribeElectionStarted()(implicit probe: TestProbe): Unit =
    system.eventStream.subscribe(probe.ref, classOf[ElectionStarted])

  def subscribeBeginAsFollower()(implicit probe: TestProbe): Unit =
    system.eventStream.subscribe(probe.ref, classOf[BeginAsFollower])

  def subscribeTermUpdated()(implicit probe: TestProbe): Unit =
    system.eventStream.subscribe(probe.ref, classOf[TermUpdated])

  def awaitBeginElection(max: FiniteDuration = DefaultTimeoutDuration)(implicit probe: TestProbe): Unit =
    probe.expectMsgClass(max, BeginElection.getClass)

  def awaitBeginAsFollower(max: FiniteDuration = DefaultTimeoutDuration)
                          (implicit probe: TestProbe): BeginAsFollower =
    probe.expectMsgClass(max, classOf[BeginAsFollower])




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
