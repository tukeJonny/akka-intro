package aia.routing

import akka.actor.{ Props, ActorRef, Actor }
import scala.collection.mutable.ListBuffer

object CarOptions extends Enumeration {
  val CAR_COLOR_GRAY, NAVIGATION, PARKING_SENSORS = Value
}

// 注文項目
case class Order(options: Seq[CarOptions.Value])

// 車(色、カーナビが必要か、パーキングセンサーが必要か）
case class Car(color: String = "", hasNavigation: Boolean = false, hasParkingSensors: Boolean = false)

class PaintCar(color: String) extends Actor with RouteSlip {
  def receive = {
    // SlipRouterからメッセージを受信
    case RouteSlipMessage(routeSlip, car: Car) => {
      sendMessageToNextTask(routeSlip, car.copy(color = color))
    }
  }
}

class AddNavigation() extends Actor with RouteSlip {
  def receive = {
    case RouteSlipMessage(routeSlip, car: Car) => {
      sendMessageToNextTask(routeSlip, car.copy(hasNavigation = true))
    }
  }
}

class AddParkingSensors() extends Actor with RouteSlip {
  def receive = {
    case RouteSlipMessage(routeSlip, car: Car) => {
      sendMessageToNextTask(routeSlip, car.copy(hasParkingSensors = true))
    }
  }
}

// SlipRouterのメッセージ.
case class RouteSlipMessage(routeSlip: Seq[ActorRef], message: AnyRef)

trait RouteSlip {
  // 次のタスクにメッセージを送る
  def sendMessageToNextTask(routeSlip: Seq[ActorRef], message: AnyRef) {
    val nextTask = routeSlip.head
    val newSlip = routeSlip.tail
    if (newSlip.isEmpty) {
      nextTask ! message
    } else {
      nextTask ! RouteSlipMessage(
        routeSlip = newSlip,
        message = message)
    }
  }
}

class SlipRouter(endStep: ActorRef) extends Actor
  with RouteSlip {
  val paintBlack = context.actorOf(Props(new PaintCar("black")), "paintBlack")
  val paintGray = context.actorOf(Props(new PaintCar("gray")), "paintGray")
  val addNavigation = context.actorOf(Props[AddNavigation], "navigation")
  val addParkingSensor = context.actorOf(Props[AddParkingSensors], "parkingSensors")

  def receive = {
    // 注文を受け
    // 車のインスタンスを作って次のタスクに回す
    case order: Order => {
      val routeSlip = createRouteSlip(order.options)
      sendMessageToNextTask(routeSlip, new Car)
    }
  }

  // ここにルーティングロジックが入ってる
  private def createRouteSlip(options: Seq[CarOptions.Value]): Seq[ActorRef] = {
    val routeSlip = new ListBuffer[ActorRef]

    // Grayに染めないなら黒染めで
    if (!options.contains(CarOptions.CAR_COLOR_GRAY)) {
      routeSlip += paintBlack
    }

    // Add options
    options.foreach {
      case CarOptions.CAR_COLOR_GRAY =>
        routeSlip += paintGray
      case CarOptions.NAVIGATION =>
        routeSlip += addNavigation
      case CarOptions.PARKING_SENSORS =>
        routeSlip += addParkingSensor
      case other => // do nothing
    }

    // end
    routeSlip += endStep
    routeSlip
  }
}

