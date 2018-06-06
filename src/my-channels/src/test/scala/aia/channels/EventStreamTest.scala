package aia.channels

import akka.testkit.{ TestProbe, TestKit }
import akka.actor.ActorSystem
import org.scalatest.{ WordSpecLike, BeforeAndAfterAll, MustMatchers }
import java.util.Date
import scala.concurrent.duration._

class CancelOrder(time: Date,
                  override val customerId: String,
                  override val productId: String,
                  override val number: Int)
  extends Order(customerId, productId, number)

class EventStreamTest extends TestKit(ActorSystem("EventStreamTest"))
  with WordSpecLike
  with BeforeAndAfterAll
  with MustMatchers {

  override def afterAll(): Unit = {
    system.terminate()
  }

  "EventStream" must {

    "distribute messages" in {
      val deliverOrder = TestProbe()
      val giftModule = TestProbe()

      // deliverOrderアクターとgiftModuleアクターがOrderメッセージを
      // サブスクライブするようにEventStreamに設定する
      // subscribeの第２引数は、Classifierを指定する。
      // EventStreamのClassifierは、型なので、classOf[Order]で
      // Order型を渡してあげている
      system.eventStream.subscribe(deliverOrder.ref, classOf[Order])
      system.eventStream.subscribe(giftModule.ref, classOf[Order])

      // 新しいOrderメッセージを作成し、パブリッシュする
      val msg = new Order(customerId = "me", productId = "Akka in Action", number = 2)
      system.eventStream.publish(msg)

      // サブスクライブ側のアクタ−２つはメッセージを受信できるはずである
      deliverOrder.expectMsg(msg)
      giftModule.expectMsg(msg)
    }

    "monitor hierarchy" in {
      val giftModule = TestProbe()

      // eventStreamでgiftModule.refがOrderメッセージをサブスクライブ
      // できるように設定する
      system.eventStream.subscribe(giftModule.ref, classOf[Order])

      // メッセージをEventStreamに流す
      val msg = new Order("me", "Akka in Action", 3)
      system.eventStream.publish(msg)

      // giftModuleはメッセージを受け取れるはず
      giftModule.expectMsg(msg)

      // ここがこのテストの重要なところ
      // CancelOrderはOrderを継承しているため、
      val msg2 = new CancelOrder(new Date(), "me", "Akka in Action", 2)
      system.eventStream.publish(msg2)

      // giftModuleはこの継承されたメッセージも受け取れるはずである
      giftModule.expectMsg(msg2)
    }

    // 確かにCancelOrderはOrderを継承しているが、
    // CancelOrderのみサブスクライブする場合は、
    // Orderだからと言ってCancelOrderとは限らないため、受信できないはず
    "Ignore other messages" in {
      val giftModule = TestProbe()

      system.eventStream.subscribe(giftModule.ref, classOf[CancelOrder])

      val msg = new Order("me", "Akka in Action", 3)
      system.eventStream.publish(msg)
      giftModule.expectNoMsg(3 seconds)
    }

    "unsubscribe messages" in {
      val DeliverOrder = TestProbe()
      val giftModule = TestProbe()

      system.eventStream.subscribe(DeliverOrder.ref, classOf[Order])
      system.eventStream.subscribe(giftModule.ref, classOf[Order])

      val msg = new Order("me", "Akka in Action", 3)
      system.eventStream.publish(msg)

      DeliverOrder.expectMsg(msg)
      giftModule.expectMsg(msg)

      // giftModuleをアンサブスクライブする
      system.eventStream.unsubscribe(giftModule.ref)

      // giftModuleはメッセージを受信できなくなる
      system.eventStream.publish(msg)
      DeliverOrder.expectMsg(msg)
      giftModule.expectNoMsg(3 seconds)
    }
  }

  "OrderMessageBus" must {
    "deliver Order messages" in {
      val bus = new OrderMessageBus

      // １つの本購入と複数本購入のテストプローブを用意
      val singleBooks = TestProbe()
      val multiBooks = TestProbe()

      // busでfalseの場合はsingle, 
      // trueの場合はmultiがサブスクライブするように設定
      bus.subscribe(singleBooks.ref, false)
      bus.subscribe(multiBooks.ref, true)

      // 単一の本購入の場合、singleだけが受け取るはず
      val msg = new Order("me", "Akka in Action", 1)
      bus.publish(msg)
      singleBooks.expectMsg(msg)
      multiBooks.expectNoMsg(3 seconds)

      // 複数の本購入（さっきのも合わせて）になるので、multiだけ受け取るはず
      val msg2 = new Order("me", "Akka in Action", 3)
      bus.publish(msg)
      singleBooks.expectNoMsg(3 seconds)
      multiBooks.expectMsg(msg2)
    }

    "deliver order messages when multiple subscriber" in {
      val bus = new OrderMessageBus

      val listener = TestProbe()
      bus.subscribe(listener.ref, true)
      bus.subscribe(listener.ref, false)

      val msg = new Order("me", "Akka in Action", 1)
      bus.publish(msg)
      listener.expectMsg(msg)

      val msg2 = new Order("me", "Akka in Action", 3)
      bus.publish(msg2)
      listener.expectMsg(msg2)
    }
  }
}
