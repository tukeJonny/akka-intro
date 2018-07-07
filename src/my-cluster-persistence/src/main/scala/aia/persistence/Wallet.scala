package aia.persistence

import akka.actor._
import akka.persistence._

object Wallet {
  def props(shopperId: Long, cash: BigDecimal) =
    Props(new Wallet(shopperId, cash))
  def name(shopperId: Long) =
    s"wallet_${shopperId}"

  sealed trait Command extends Shopper.Command
  case class Pay(items: List[Item], shopperId: Long) extends Command
  case class Check(shopperId: Long) extends Command
  case class SpentHowMuch(shopperId: Long) extends Command

  case class AmountSpent(amount: BigDecimal)
  case class NotEnoughCash(left: BigDecimal)
  case class Cash(left: BigDecimal)

  sealed trait Event
  case class Paid(list: List[Item], shopperId: Long) extends Event
}

class Wallet(shopperId: Long, cash: BigDecimal) extends PersistentActor
  with ActorLogging {

  import Wallet._

  var amountSpent: BigDecimal = 0
  def persistenceId =
    s"${self.path.name}"

  def receiveCommand = {
    case Pay(items, _) =>
      val totalSpent = addSpending(items)
      if (cash - totalSpent > 0) {
        // 課金情報を永続化
        persist(Paid(items, shopperId)) { paid =>
          updateState(paid)
          sender() ! paid
        }
      } else {
        context.system.eventStream.publish(NotEnoughCash(cash - amountSpent))
      }
    case Check(_) =>
      sender() ! Cash(cash - amountSpent)
    case SpentHowMuch(_) =>
      sender() ! AmountSpent(amountSpent)
  }

  def receiveRecover = {
    // 回復したらステートを更新
    case event: Event =>
      updateState(event)
  }

  private val updateState: (Event => Unit) = {
    // 支払ったアイテムの合計を持っておく
    case paidItems @ Paid(items, _) =>
      amountSpent = addSpending(items)
  }

  private def addSpending(items: List[Item]) =
    // 小計をBigDecimalで足し合わせて、amountSpentに足し入れる
    amountSpent + items.foldLeft(BigDecimal(0)) { (total, item) =>
      total + (item.unitPrice * item.number)
    }
}
