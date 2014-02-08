package pl.project13.scala.akka.raft

import pl.project13.scala.akka.raft.model.{RaftSnapshotMetadata, RaftSnapshot}
import scala.concurrent.Future

/**
 * The main API trait for RaftActors.
 * By implementing these methods you're implementing the replicated state machine.
 *
 * Messages sent to `apply` are guaranteed by Raft to have been '''committed''', so they're safe to apply to your STM.
 *
 * In order to start using replicated log snapshotting, you just need to override the `prepareSnapshot` method,
 * which will be called each time Raft decides to take a snapshot of the replicated journal (how often that happens is configurable).
 */
trait ReplicatedStateMachine {

  type ReplicatedStateMachineApply = PartialFunction[Any, Any]
  
  /**
   * Use this method to change the actor's internal state.
   * It will be called with whenever a message is committed by the raft cluster.
   *
   * Please note that this is different than a plain `receive`, because the returned value from application
   * will be sent back _by the leader_ to the client that originaly has sent the message.
   *
   * All other __Followers__ will also apply this message to their internal state machines when it's committed,
   * although the result of those applications will ''not'' be sent back to the client who originally sent the message -
   * only the __Leader__ responds to the client (`1 message <-> 1 response`). Although you're free to use `!` inside an
   * apply (resulting in possibly `1 message <-> n messages`).
   *
   * You can treat this as a raft equivalent of receive, with the difference that apply is guaranteed to be called,
   * only after the message has been propagated to the majority of members.
   *
   * '''Log compaction and snapshots''':
   * Match for [[pl.project13.scala.akka.raft.protocol.RaftProtocol.InstallSnapshot]] in order to install snapshots
   * to your internal state machine if you're using log compactation (see `prepareSnapshot`)
   *
   * @return the returned value will be sent back to the client issuing the command.
   *         The reply is only sent once, by the current raft leader.
   */
  def apply: ReplicatedStateMachineApply


  /**
   * Called whenever compaction is performed on the raft replicated log.
   *
   * Log compaction is required in order to maintain long running raft clusters
   *
   * The produced snapshot MUST include [[pl.project13.scala.akka.raft.model.RaftSnapshotMetadata]]
   * obtained as the parameter in this call. The simplest snapshotting example would be to complete with the current state:
   *
   * {{{
   *   class SummingActor extends RaftActor {
   *     var sum: Int = 0
   *
   *     def receive = { case i: Int => sum += i }
   *
   *     // compaction is simple, we can just store the current state
   *     def onCompaction(meta: RaftSnapshotMetadata) =
   *       Future(Some(RaftSnapshot(meta, sum)))
   *   }
   * }}}
   *
   * @return if you don't want to store a snapshot (default impl), you can just complete the Future with None,
   *         otherwise, return the snapshot data you want to store
   */
  def prepareSnapshot(snapshotMetadata: RaftSnapshotMetadata): Future[Option[RaftSnapshot]] =
    Future.successful(None)
}
