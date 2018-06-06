package aia.routing

import akka.actor.{ ActorRef, Actor }
import akka.routing.ConsistentHashingRouter.ConsistentHashable

trait GatherMessage {
  val id: String
  val value: Seq[String]
}

case class GatherMessageNormalImpl(id: String, value: Seq[String]) extends GatherMessage
case class GatherMessageWithHash(id: String, value: Seq[String]) extends GatherMessage with ConsistentHashable

class SimpleGather(nextStep: ActorRef) extends Actor {
  var messages = Map[String, GatherMessage]()

  def receive = {
    case msg: GatherMessage => {
      messages.get(msg.id) match {
        case Some(previous) => {
          // メッセージを結合して、nextStepに送り出し
          // 、Mapから取り除く
          nextStep ! GatherMessageNormalImpl(msg.id, previous.values ++ msg.values)
          messages -= msg.id
        }
        case None =>
          messages += msg.id -> msg
      }
    }
  }
}
