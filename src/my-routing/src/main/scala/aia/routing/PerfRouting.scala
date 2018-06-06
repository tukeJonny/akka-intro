package aia.routing

import akka.actor._
import akka.routing._

class TestSuper() extends Actor {
  def receive = {
    case "OK" =>
    case _ => throw new IllegalArgumentException("not supported")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    println("restart %s".format(self.path.toString))
  }
}

class GetLicenseCreator(nrActors: Int, nextStep: ActorRef) extends Actor {
  var createdActors = Seq[ActorRef]()

  // 開始前にnrActors個のGetLicenseアクターを生成し、createdActorsに格納
  override def preStart(): Unit = {
    super.preStart()
    createdActors = (0 until nrActors).map(nr => {
      context.actorOf(Props(new GetLicense(nextStep)), "GetLicense" + nr)
    })
  }

  def receive = {
    // createdActorsの先頭要素のアクターに対してKillメッセージを送って殺す
    case "KillFirst" => {
      createdActors.headOption.foreach(_ ! Kill)
      createdActors = createdActors.tail
    }
    case _ =>
      throw new IllegalArgumentException("not supported")
  }

  // 再起動処理前デバッグ
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    println("restart %s".format(self.path.toString))
  }
}

// 子アクターが終了した時に新しいアクターを生成するようにしたGetLicenseCreator
class GetLicenseCreator(nrActors: Int, nextStep: ActorRef) extends Actor {
  override def preStart(): Unit = {
    super.preStart()
    (0 until nrActors).map(nr => {
      val child = context.actorOf(Props(new GetLicense(nextStep)), "GetLicense" + nr)
      context.watch(child)
    })
  }

  def receive = {
    // 最初の子供に毒薬飲ませて殺す
    case "KillFirst" => {
      if (!context.children.isEmpty) {
        context.children.head ! PoisonPill
      }
    }
    // 死んだら作り直す
    case Terminated(child) => {
      val newChild = context.actorOf(Props(new GetLicense(nextStep)), child.path.name)
      context.watch(newChild)
    }
  }
}

case class PreferredSize(size: Int)

class DynamicRouteeSizer(nrActors: Int, props: Props, router: ActorRef) extends Actor {
  val nrChildren = nrActors
  val childInstanceNr = 0

  override def preStart(): Unit = {
    super.preStart()
    (0 until nrChildren).map(nr => createRoutee())
  }

  // 新しくルーティを作成
  // アクターを生成し、ActorRefを得たら、それを元にルーターにルーティ追加
  // メッセージを送る
  def createRoutee(): Unit = {
    childInstanceNr += 1
    val child = context.actorOf(props, "routee" + childInstanceNr)
    val selection = context.actorSelection(child.path)

    router ! AddRoutee(ActorSelectionRoutee(selection))
    context.watch(child)
  }

  def receive = {
    // sizeに調整するように要求が来た。
    case PreferredSize(size) => {
      if (size < nrChildren) {
        // nrChildrenの方が大きくて、確かにこりゃ調整必要だとなったら
        // 余剰分だけルーティ削除メッセージをルーターに送る
        context.children.take(nrChildren - size).foreach(ref => {
          val selection = context.actorSelection(ref.path)
          router ! RemoveRoutee(ActorSelectionRoutee(selection))
        })
        router ! GetRoutees
      } else {
        // 逆で、ルーティ足りないので増やす
        (nrChildren until size).map(nr => createRoutee())
      }
    }
    // 更新後に送ったGetRouteesの返答が帰って来た
    case routees: Routees => {
      import collection.JavaConversions._

      // Routees -> actorPath
      val active = routees.getRoutees.map {
        case x: ActorRefRoutee =>
          x.ref.path.toString
        case x: ActorSelectionRoutee =>
          x.selection.pathString
      }

      // ルーティリストの処理
      for (routee <- context.children) {
        val index = active.indexOf(routee.path.toStringWithoutAddress)
        if (index >= 0) { // ルーターに登録されたままなので、まずは消す
          active.remove(index)
        } else { // ルーターにはもう登録されてないので、毒薬飲ませて殺す
          routee ! PoisonPill
        }
      }

      for (terminated <- active) {
        // /hoge/fuga/piyo -> piyo
        val name = terminated.substring(terminated.lastIndexOf("/") + 1)
        // 理由はわからないが、停止してしまった子アクターを再起動する
        val child = context.actorOf(props, name)
        // 監視
        context.watch(child)
      }
    }
    case Terminated(child) => // 意図した死なのかちゃんとチェックする
      router ! GetRoutees
  }
}
