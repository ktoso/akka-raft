//package pl.project13.scala.akka.raft
//
//import pl.project13.scala.akka.raft.protocol._
//import concurrent.duration._
//import akka.testkit.TestProbe
//
//class LogBackfillingTest extends RaftSpec {
//
//  behavior of "Log Backfilling"
//
//  val memberCount = 3
//
//  val timeout = 200.millis
//
//  it should "resend all commands to a node that became reachable" in {
//    // given
//    subscribeElectedLeader()
//    awaitElectedLeader()
//    infoMemberStates()
//
//    val unstableMemberListener = TestProbe()
//
//    // when
//    val unstableFollower = followers.head
//    unstableMemberListener.ref ! ClientMessage(probe.ref, AddListener(unstableMemberListener.ref))
//    Thread.sleep(100)
//    suspendMember(unstableFollower)
//
//    leader ! ClientMessage(probe.ref, AppendWord("I"))
//    leader ! ClientMessage(probe.ref, AppendWord("like"))
//    leader ! ClientMessage(probe.ref, AppendWord("bananas"))
//
//    restartMember(unstableFollower)
//
//
//    // then
//    // probe will get the messages once they're confirmed by quorum
//    unstableMemberListener.expectMsgClass(timeout, classOf[ClientMessage[AddListener]])
//    unstableMemberListener.expectMsg(timeout, "I")
//    unstableMemberListener.expectMsg(timeout, "like")
//    unstableMemberListener.expectMsg(timeout, "bananas")
//  }
//
//}
