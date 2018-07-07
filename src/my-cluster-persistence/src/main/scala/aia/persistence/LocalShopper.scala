package aia.persistence

import akka.actor._

object LocalShoppers {
  def props =
    Props(new LocalShoppers)

  def name =
    "local-shoppers"
}

class LocalShoppers extends Actor
  with ShopperLookup {
  def receive =
    forwardToShopper
}

trait ShopperLookup {
  implicit def context: ActorContext

  def forwardCommand(cmd: Shopper.Command)(shopper: ActorRef) =
    shopper forward cmd

  // shopperを作成
  def createShopper(shopperId: Long) =
    context.actorOf(Shopper.props(shopperId), Shopper.name(shopperId))

  def createAndForward(cmd: Shopper.Command, shopperId: Long) =
    createShopper(shopperId) forward cmd

  // 受け取ったcmdを全Shopperに送信する
  def forwardToShopper: Actor.Receive = {
    case cmd: Shopper.Command => {
      val shopperName = Shopper.name(cmd.shopperId)
      // context.child(shopperName) の各shopperについて、forward cmdを関数適用
      context.child(shopperName).fold(createAndForward(cmd, cmd.shopperId))(forwardCommand(cmd))
    }
  }
}
