package aia.routing

import scala.concurrent.duration._
import scala.collection.immutable

import akka.actor._
import akka.dispatch.Dispatchers
import akka.routing._

case class PerformanceRoutingMessage(photo: String, license: Option[String], processedBy: Option[String])
case class SetService(id: String, serviceTime: FiniteDuration)

class GetLicense(pipe: ActorRef, initialServiceTime: FiniteDuration = 0 millis) extends Actor {
  val id = self.path.name
  val serviceTime = initialServiceTime

  def receive = {
    // サービスを初期化
    case init: SetService => {
      id = init.id
      serviceTime = init.serviceTime
      Thread.sleep(100)
    }

    // ライセンスを取得、自身のidをぶち込んでパイプに流す
    case msg: PerformanceRoutingMessage => {
      Thread.sleep(serviceTime.toMillis)
      pipe ! msg.copy(license = ImageProcessing.getLicense(msg.photo), processedBy = Some(id))
    }
  }
}

// パイプにメッセージをそのまま流す
// まあ言い換えれば、pipeが指す先にメッセージをリダイレクトする
// みたいな感じかな
class RedirectActor(pipe: ActorRef) extends Actor {
  println("RedirectActor instance created")

  def receive = {
    case msg: AnyRef => {
      pipe ! msg
    }
  }
}

// カスタムRouterLogicを作る
// RoutingLogicを継承し、
class SpeedRouterLogic(minSpeed: Int, normalFlowPath: String, cleanUpPath: String) extends RoutingLogic {
  // PhotoMessageの行先を選択する
  def select(message: Any, routees: immutable.IndexedSeq[Routee]): Routee = {
    message match {
      case msg: Photo =>
        if (msg.speed > minSpeed) // もしスピードが大丈夫そうなら、普通のフローに流す
          findRoutee(routees, normalFlowPath)
        else // スピードダメそうならcleanUpフローに流す
          findRoutee(routees, cleanUpPath)
    }
  }

  // pathに一致するルーティを探す
  def findRoutee(routees: immutable.IndexedSeq[Routee], path: String): Routee = {
    // routeeがActorRefRoutee型の場合はそのまま、
    // SeveralRouteesメッセージでrouteeSeqを持つならrouteeSeqを返す
    val routeeList = routees.flatMap {
      case routee: ActorRefRoutee =>
        routees
      case SeveralRoutees(routeeSeq) =>
        routeeSeq
    }
    // パスに一致するルーティを検索
    val search = routeeList.find {
      case routee: ActorRefRoutee =>
        routee.ref.path.toString().endsWith(path)
    }

    // 見つかればそれを、なければNoRouteeを返す
    search.getOrElse(NoRoutee)
  }
}

// カスタムプールを定義
case class SpeedRouterPool(minSpeed: Int, normalFlow: Props, cleanUp: Props) extends Pool {
  def nrOfInstances(sys: ActorSystem): Int = 1
  def resizer: Option[Resizer] = None
  def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy()(SupervisorStrategy.defaultDecider)

  override def createRouter(system: ActorSysteem): Router = {
    new Router(new SpeedRouterLogic(minSpeed, "normalFlow", "cleanup"))
  }

  override val routerDispatcher: String =
    Dispatchers.DefaultDispatcherId

  override def newRoutee(routeeProps: Props, context: ActorContext): Routee = {
    // ルーティを生成
    val normal = context.actorOf(normalFlow, "normalFlow")
    val clean = context.actorOf(cleanUp, "cleanup")

    SeveralRoutees(immutable.IndexedSeq[Routee](
      ActorRefRoutee(normal),
      ActorRefRoutee(clean)))
  }
}

// ルーターの状態を変更するためのメッセージ
case class RouteStateOn()
case class RouteStateOff()

class SwitchRouter(normalFlow: ActorRef, cleanUp: ActorRef) extends Actor
  with ActorLogging {
  // onモード (通常のフローに流していく)
  def on: Receive = {
    case RouteStateOn =>
      log.warning("Received on while already in on state")
    case RouteStateOff =>
      context.become(off) // OFFモードに移行
    case msg: AnyRef =>
      normalFlow ! msg
  }

  // offモード (クリーンアップのフローに流していく)
  def off: Receive = {
    case RouteStateOn =>
      context.become(on)
    case RoutestateOff =>
      log.warning("Received off while already in off state")
    case msg: AnyRef =>
      cleanUp ! msg
  }

  // 初期はoffモードで動作
  def receive = {
    case msg: AnyRef =>
      off(msg)
  }
}

// unbecome()を使う
// become() -> unbecome() で一つ前の状態に戻れるようす
class SwitchRouter2(normalFlow: ActorRef, cleanUp: ActorRef) extends Actor
  with ActorLogging {

  def on: Receive = {
    case RouteStateOn =>
      log.warning("Received on while already in on state")
    case RouteStateOff =>
      context.unbecome()
    case msg: AnyRef =>
      normalFlow ! msg
  }

  def off: Receive = {
    case RouteStateOn =>
      context.become(on)
    case RouteStateOff =>
      log.warning("Received off while already in off state")
    case msg: AnyRef => {
      cleanUp ! msg
    }
  }

  def receive = {
    case msg: AnyRef =>
      off(msg)
  }
}
