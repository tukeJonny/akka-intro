package aia.faulttolerance

import aia.faulttolerance.LifeCycleHooks.{ ForceRestart, ForceRestartException }
import akka.actor._

// LifeCycleHooks Companion Object
object LifeCycleHooks {
  object SampleMessage
  object ForceRestart

  private class ForceRestartException extends IllegalArgumentException("force restart")
}

class LifeCycleHooks extends Actor with ActorLogging {
  // Constructor called
  log.info("Constructor")

  // Pre starting hook
  override def preStart(): Unit = {
    log.info("preStart")
  }

  // Post stopping hook
  override def postStop(): Unit = {
    log.info("postStop")
  }

  // Pre restarting hook
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    log.info(s"preRestart. Reason: $reason when handling message: $message")

    // WARN: DO NOT FORGET CALL SUPER preRestart() !!!
    super.preRestart(reason, message)
  }

  // Post restarting hook
  override def postRestart(reason: Throwable): Unit = {
    log.info("postRestart")

    super.postRestart(reason)
  }

  // Receiving messages
  def receive = {
    case ForceRestart =>
      throw new ForceRestartException
    // そのまま送り返す
    case msg: AnyRef =>
      log.info(s"Received: '$msg'. Sending back")
      sender() ! msg
  }
}
