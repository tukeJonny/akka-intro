package aia.channels




case class StateEvent(time: Date, state: String)
case class Connection(time: Date, connected: Boolean)

class StateEndpoint extends Actor {
  def receive = {
    case Connection(time, true) => {
      context.system.eventStream.publish(new StateEvent(time, "Connected"))
    }
    case Connection(time, false) => {
      context.system.eventStream.publish(new StateEvent(time, "Disconnected"))
    }
  }
}

class SystemLog extends Actor {
  def receive = {
    case event:StateEvent =>
  }
}

class SystemMonitor extends Actor {
  def receive = {
    case event:StateEvent =>
  }
}

import akka.event.ActorEventBus
import akka.event.{ LookupClassification, EventBus }

// LookupClassification ... 基本的な分類を行う
// SubchannelClassification ... Classifierが階層構造で、リーフだけでなく
// 上位ノードもサブスクライブしたい場合に使える
// これはEventStreamでも使っていて、Orderを継承したCancelOrderがある時に、OrderのサイブスクライバがCancelOrderも受信できるのはこれが使われているからである
// ScanningClassification ... ２冊以上で栞、１１冊以上で本１冊って特典がある場合を実装したいときに使える。

class OrderMessageBus extends EventBus
  with LookupClassification
  with ActorEventBus {
  // このチャネルで扱うイベントをOrderとする
  type Event = Order

  // このチャネルでのメッセージ分類はBooleanを用いる
  type Classifier = Boolean

  def mapSize = 2 // 複数書籍の注文であるか否かの２つしかないので、mapSizeは2に指定

  // classifyはLookupClassificationトレイトの具象化をしている
  // OrderMessageBusのEvent型であるイベントを引数に取り、
  // 分類を行う(返り値はClassifier(=Boolean)型)
  protected def classify(event: OrderMessageBus#Event) = {
    event.number > 1
  }

  // publishはActorEventBusトレイトの具象化をしている
  // ActorEventBusトレイトは、LookupClassificationで抽象定義された
  // compareSubscribersメソッドの定義をしている便利
  // イベントをサブスクライバーに送信するように、publishメソッドを定義
  protected def publish(event: OrderMessageBus#Event,
                        subscriber: OrderMessageBus#Subscriber) {
    subscriber ! event
  }
}
