package aia.state

import akka.testkit.{ TestProbe, ImplicitSender, TestKit }
import akka.actor.{ Props, ActorSystem }
import org.scalatest.{ WordSpecLike, BeforeAndAfterAll, MustMatchers }
import akka.actor.FSM.{ Transaction, CurrentState, SubscribeTransactionCallback }
import concurrent.duration._

class InventoryTest extends TestKit(ActorSystem("InventoryTest"))
  with WordSpecLike
  with BeforeAndAfterAll
  with MustMatchers
  with ImplicitSender {

  override def afterAll(): Unit = {
    system.terminate()
  }

  "Inventory" must {

    "follow the flow" in {
      val publisher = system.actorOf(Props(new Publisher(2, 2)))
      val inventory = system.actorOf(Props(new Inventory(publisher)))

      val stateProbe = TestProbe() // 状態遷移の通知をサブスクライブ
      val replyProbe = TestProbe() // 応答通知をサブスクライブ

      // 状態遷移の通知をstateProbeでサブスクライブ（監視）できるようにする
      // 応答にCurrentState(=現在の状態)が返ってくるはずである。
      inventory ! new SubscribeTransactionCallback(stateProbe.ref)
      stateProbe.expectMsg(new CurrentState(inventory, WaitForRequests))

      // ここからFSMのテスト開始

      // 初期状態から新たに本の注文をした場合
      // 注文待ち -> 出版社待ち -> 注文処理中 -> （BookReply) -> 注文待ち
      // と遷移するはずである
      inventory ! new BookRequest("context1", replyProbe.ref)
      stateProbe.expectMsg(new Transaction(inventory, WaitForRequests, WaitForPublisher))
      stateProbe.expectMsg(new Transaction(inventory, WaitForPublisher, ProcessRequest))
      stateProbe.expectMsg(new Transaction(inventory, ProcessRequest, WaitForRequests))
      stateProbe.expectMsg(new BookReply("context1", Right(1)))

      // 注文毎の本取得数が２なので、context1の注文時に出版社から２冊仕入れているので、在庫があるはずで、
      // 注文待ち -> 注文処理中 -> (BookReply) -> 注文待ち
      // と遷移するはずである
      inventory ! new BookRequest("context2", replyProbe.ref)
      stateProbe.expectMsg(new Transaction(inventory, WaitForRequests, ProcessRequest))
      stateProbe.expectMsg(new Transaction(inventory, ProcessRequest, WaitForRequests))
      stateProbe.expectMsg(new BookReply("context2", Right(2)))

      // 売り切れになるはずである
      // 注文待ち -> 出版社待ち -> 売り切れ -> (BookReply) -> 売り切れ
      inventory ! new BookRequest("context3", replyProbe.ref)
      stateProbe.expectMsg(new Transaction(inventory, WaitForRequests, WaitForPublisher))
      stateProbe.expectMsg(new Transaction(inventory, WaitForPublisher, ProcessSoldOut))
      stateProbe.expectMsg(new BookReply("context3", Left("SoldOut")))
      stateProbe.expectMsg(new Transaction(inventory, ProcessSoldOut, SoldOut))

      // もう売り切れになることがわかっているので、ProcessSoldOutは通らない
      // 注文待ち -> 出版社待ち -> 売り切れ
      inventory ! new BookRequest("context3", replyProbe.ref)
      stateProbe.expectMsg(new Transaction(inventory, WaitForRequests, WaitForPublisher))
      stateProbe.expectMsg(new Transaction(inventory, WaitForPublisher, ProcessSoldOut))
      stateProbe.expectMsg(new BookReply("context3", Left("SoldOut")))
      stateProbe.expectMsg(new Transaction(inventory, ProcessSoldOut, SoldOut))

    }

    // 単一のメッセージでテストしているが、包括的に複数のメッセージがきた場合の挙動もテストすべき
    "process multiple requests" in {

    }

    "support multiple supplies" in {

    }
  }

  "InventoryTimer" must {
    "follow the flow" in {
      val publisher = TestProbe()
      val inventory = system.actorOf(Props(new InventoryWithTimer(publisher.ref)))

      val stateProbe = TestProbe()
      val replyProbe = TestProbe()

      // 状態遷移の変更をサブスクライブするようにする
      inventory ! new SubscribeTransitionCallBack(stateProbe.ref)
      stateProbe.expectMsg(new CurrentState(inventory, WaitForRequests))

      // テスト開始
      // まずはリクエスト待ちから出版社待ちに遷移し、
      inventory ! new BookRequest("context 1", replyProbe.ref)
      stateProbe.expectMsg(new Transition(inventory, WaitForRequests, WaitForPublisher))
      publisher.expectMsg(PublisherRequest)

      // 6秒待ってタイムアウト後に出版社待ちから注文待ちに遷移する
      stateProbe.expectMsg(6 seconds, new Transition(
        inventory,
        WaitForPublisher,
        WaitForRequests)
      )
      // そして、リクエスト待ちから出版待ちに再度移行する
      stateProbe.expectMsg(new Transition(inventory, WaitForRequests, WaitForPublisher))

      system.stop(inventory)
    }
  }
}
