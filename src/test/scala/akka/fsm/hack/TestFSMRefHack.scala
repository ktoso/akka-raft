package akka.fsm.hack

import akka.actor._
import scala.reflect.ClassTag
import akka.testkit.{TestActorRef, TestFSMRef}

object TestFSMRefHack {

  def apply[S, D, T <: Actor: ClassTag](props: Props, name: String)(implicit ev: T <:< FSM[S, D], system: ActorSystem): TestFSMRef[S, D, T] = {
    val impl = system.asInstanceOf[ActorSystemImpl] //TODO ticket #1559
    new TestFSMRef(impl, props, impl.guardian.asInstanceOf[InternalActorRef], name)
  }



}
