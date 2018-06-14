package aia.state

import akka.actor.{ ActorRef, Actor, FSM }
import math.min
import scala.concurrent.duration._

// Events
case class BookRequest(context: AnyRef, target: ActorRef)
case class BookSupply(nrBooks: Int)
case object BookSupplySoldOut
case object Done
case object PendingRequests

// Response
case object PublisherRequest
case class BookReply(context: AnyRef, reserveId: Either[String, Int])

// States
sealed trait State
case object WaitForRequests extends State
case object ProcessRequest extends State
case object WaitForPublisher extends State
case object SoldOut extends State
case object ProcessSoldOut extends State

// 状態遷移を発生させるかどうか判断するために使うデータ
case class StateData(nrBooksInStore: Int, pendingRequests: Seq[BookRequest])

class Inventory(publisher: ActorRef) extends Actor
  with FSM[State, StateData] {

  var reserveId = 0
  // 初期状態がWaitForRequests, 
  // 初期データがStateData(0, Seq())
  startWith(WaitForRequests, new StateData(0, Seq()))

  // 注文待ちの時
  when(WaitForRequests) {
    // BookRequestを受信した際に在庫があればProcessRequest, 
    // 在庫がなければWaitForPublisherへnewStateDataをステートデータ
    // として遷移する
    case Event(request: BookRequest, data: StateData) => {
      val newStateData = data.copy(pendingRequests =
        data.pendingRequests :+ request)
      if (newStateData.nrBooksInStore > 0) {
        goto(ProcessRequest) using newStateData
      } else {
        goto(WaitForPublisher) using newStateData
      }
    }

    // PendingRequestsを受信した際、保留注文がない場合はそのまま
    // 保留注文があり、かつ在庫がある場合はProcessRequestへ遷移
    // 保留注文があり、かつ在庫がない場合はWaitForPublisherへ遷移
    // して出版社を待つ
    case Event(PendingRequests, data: StateData) => {
      if (data.pendingRequests.isEmpty) {
        stay
      } else if (data.nrBooksInStore > 0) {
        goto(ProcessRequest)
      } else {
        goto(WaitForPublisher)
      }
    }
  }

  // 出版社待ちの時
  when(WaitForPublisher) {
    // 本の供給メッセージがきたら、在庫を供給分に更新して
    // ProcessRequestへ遷移する
    case Event(supply: BookSupply, data: StateData) => {
      goto(ProcessRequest) using data.copy(nrBookInStore =
        supply.nrBooks)
    }

    // 売り切れ
    case Event(BookSuplySoldOut, _) => {
      goto(ProcessSoldOut)
    }
  }

  // 注文処理中の時
  when(ProcessRequest) {
    // 注文が完了したら、在庫数を１減らし、pendingRequestsを更新
    case Event(Done, data: StateData) => {
      goto(WaitForRequests) using data.copy(
        nrBooksInStore = data.nrBooksInStore - 1,
        pendingRequests = data.pendingRequests.tail)
    }
  }

  // 売り切れの時
  when(SoldOut) {
    // 売り切れ処理中状態へ遷移する
    case Event(request: BookRequest, data: StateData) => {
      goto(ProcessSoldOut) using new StateData(0, Seq(request))
    }
  }

  // 売り切れ処理中状態の時
  when(ProcessSoldOut) {
    // pendingを空にして売り切れに戻る
    case Event(Done, data: StateData) => {
      goto(SoldOut) using new StateData(0, Seq())
    }
  }

  // whenで処理されないメッセージの時
  // otherwiseやdefault的なもの
  whenUnhandled {
    // 本の注文が来たら、pendingRequestに加えておく
    case Event(request: BookRequest, data: StateData) => {
      stay using data.copy(pendingRequests =
        data.pendingRequests :+ request)
    }
    case Event(e, s) => {
      log.warning("Received unhandled request {} in state {}/{}",
        e, stateName, s)
      stay
    }
  }

  // 初期状態(=上部にてstartWithを定義している内容) に遷移させて、
  // （必要に応じてタイマーを設定できる) initializeでFSMを起動する
  initialize

  // 開始アクション(各状態にエントリーした際に実行されるアクションの定義)
  onTransaction {
    // 任意状態 -> WaitForRequestsの遷移が発生した際に実行されるアクション
    case _ -> WaitForRequests => {
      // 
      // 自身にPendingRequestsを送る
      self ! PendingRequests
    }

    // 任意状態 -> WaitForPublisherの遷移が発生ry
    case _ -> WaitForPublisher => {
      // PublisherRequestは状態でのメッセージに使われていない
      // 出版社アクターに問い合わせするために利用され、
      // 供給できるようになるとBookSupplyメッセージが返答されて来て、
      // WaitForPublisher状態の時に在庫を追加できる
      publisher ! PublisherRequest
    }

    // 任意状態 -> 注文処理状態の遷移がry
    case _ -> ProcessRequest => {
      // 保留リクエストから取り出し、予約IDを更新し、
      // ターゲットに返答を返して、自身に注文処理が完了したことを知らせる
      val request = nextStateData.pendingRequests.head
      reserveId += 1
      request.target ! new BookReply(request.context, Right(reserveId))
      self ! Done
    }

    // 任意状態 -> 売り切れの遷移がry
    // 売り切れを現在保留中の購買者に知らせて、完了したことを自身に知らせる
    case _ -> ProcessSoldOut => {
      nextStateData.pendingRequests.foreach( request => {
        request.target ! new BookReply(request.context, Left("SoldOut"))
      })
      self ! Done
    }
  }
}

// 出版社アクター
class Publisher(totalNrBooks: Int, nrBooksPerRequest: Int) extends Actor {
  // 残本数(Leftは左ではなく、残ってるという意味)
  var nrLeft = totalNrBooks

  def receive = {
    // 本が欲しいと要求が来たら
    case PublisherRequest => {
      // 在庫数に応じて売り切れか、リクエスト毎の本
      // を返す（返せない場合は残だけでも返す)
      if (nrLeft == 0)
        sender() ! BookSupplySoldOut
      else {
        val supply = min(NrBooksPerRequest, nrLeft)
        nrLeft -= supply
        sender() ! new BookSupply(supply)
      }
    }
  }
}






class InventoryWithTimer(publisher: ActorRef) extends Actor
  with FSM[State, StateData] {

  var reserveId = 0
  // 初期状態がWaitForRequests, 
  // 初期データがStateData(0, Seq())
  startWith(WaitForRequests, new StateData(0, Seq()))

  // 注文待ちの時
  when(WaitForRequests) {
    // BookRequestを受信した際に在庫があればProcessRequest, 
    // 在庫がなければWaitForPublisherへnewStateDataをステートデータ
    // として遷移する
    case Event(request: BookRequest, data: StateData) => {
      val newStateData = data.copy(pendingRequests =
        data.pendingRequests :+ request)
      if (newStateData.nrBooksInStore > 0) {
        goto(ProcessRequest) using newStateData
      } else {
        goto(WaitForPublisher) using newStateData
      }
    }

    // PendingRequestsを受信した際、保留注文がない場合はそのまま
    // 保留注文があり、かつ在庫がある場合はProcessRequestへ遷移
    // 保留注文があり、かつ在庫がない場合はWaitForPublisherへ遷移
    // して出版社を待つ
    case Event(PendingRequests, data: StateData) => {
      if (data.pendingRequests.isEmpty) {
        stay
      } else if (data.nrBooksInStore > 0) {
        goto(ProcessRequest)
      } else {
        goto(WaitForPublisher)
      }
    }
  }

  // [ 変更(変更点はここだけ) ] !
  // stateTimeoutを設定する
  // そして、Event(StateTimeout, _)がタイムアウト時に送られてくるので
  // 送られてきたらWaitForRequestsへ遷移するように変更する
  //
  // 出版社待ちの時
  when(WaitForPublisher, stateTimeout = 5 seconds) {
    // 本の供給メッセージがきたら、在庫を供給分に更新して
    // ProcessRequestへ遷移する
    case Event(supply: BookSupply, data: StateData) => {
      goto(ProcessRequest) using data.copy(nrBookInStore =
        supply.nrBooks)
    }

    // 売り切れ
    case Event(BookSuplySoldOut, _) => {
      goto(ProcessSoldOut)
    }
    case Event(StateTimeout, _) =>
      goto(WaitForRequests)
  }

  // 注文処理中の時
  when(ProcessRequest) {
    // 注文が完了したら、在庫数を１減らし、pendingRequestsを更新
    case Event(Done, data: StateData) => {
      goto(WaitForRequests) using data.copy(
        nrBooksInStore = data.nrBooksInStore - 1,
        pendingRequests = data.pendingRequests.tail)
    }
  }

  // 売り切れの時
  when(SoldOut) {
    // 売り切れ処理中状態へ遷移する
    case Event(request: BookRequest, data: StateData) => {
      goto(ProcessSoldOut) using new StateData(0, Seq(request))
    }
  }

  // 売り切れ処理中状態の時
  when(ProcessSoldOut) {
    // pendingを空にして売り切れに戻る
    case Event(Done, data: StateData) => {
      goto(SoldOut) using new StateData(0, Seq())
    }
  }

  // whenで処理されないメッセージの時
  // otherwiseやdefault的なもの
  whenUnhandled {
    // 本の注文が来たら、pendingRequestに加えておく
    case Event(request: BookRequest, data: StateData) => {
      stay using data.copy(pendingRequests =
        data.pendingRequests :+ request)
    }
    case Event(e, s) => {
      log.warning("Received unhandled request {} in state {}/{}",
        e, stateName, s)
      stay
    }
  }

  // 初期状態(=上部にてstartWithを定義している内容) に遷移させて、
  // （必要に応じてタイマーを設定できる) initializeでFSMを起動する
  initialize

  // 開始アクション(各状態にエントリーした際に実行されるアクションの定義)
  onTransaction {
    // 任意状態 -> WaitForRequestsの遷移が発生した際に実行されるアクション
    case _ -> WaitForRequests => {
      // 
      // 自身にPendingRequestsを送る
      self ! PendingRequests
    }

    // 任意状態 -> WaitForPublisherの遷移が発生ry
    case _ -> WaitForPublisher => {
      // PublisherRequestは状態でのメッセージに使われていない
      // 出版社アクターに問い合わせするために利用され、
      // 供給できるようになるとBookSupplyメッセージが返答されて来て、
      // WaitForPublisher状態の時に在庫を追加できる
      publisher ! PublisherRequest
    }

    // 任意状態 -> 注文処理状態の遷移がry
    case _ -> ProcessRequest => {
      // 保留リクエストから取り出し、予約IDを更新し、
      // ターゲットに返答を返して、自身に注文処理が完了したことを知らせる
      val request = nextStateData.pendingRequests.head
      reserveId += 1
      request.target ! new BookReply(request.context, Right(reserveId))
      self ! Done
    }

    // 任意状態 -> 売り切れの遷移がry
    // 売り切れを現在保留中の購買者に知らせて、完了したことを自身に知らせる
    case _ -> ProcessSoldOut => {
      nextStateData.pendingRequests.foreach( request => {
        request.target ! new BookReply(request.context, Left("SoldOut"))
      })
      self ! Done
    }
  }
}




