package aia.structure

import java.util.Date
import scala.concurrent.duration._

import akka.testkit._
import akka.actor._

import org.scalatest._
import scala.language.postfixOps

class AggregatorTest extends TestKit(ActorSystem("AggregatorTest"))
  with WordSpecLike
  with BeforeAndAfterAll {
  val timeout = 2 seconds

  protected override def afterAll(): Unit = {
    system.terminate()
  }

  "The Aggregator" must {
    // ２つのメッセージを集約する
    "aggregate two messages" in {
      val endProbe = TestProbe()
      val actorRef = system.actorOf(Props(new Aggregator(timeout, endProbe.ref)))
      val photoStr = ImageProcessing.createPhotoString(new Date(), 60)

      // 時刻を解決したPhotoMessageがパイプから流れ込んでくる
      val msg1 = PhotoMessage("id1", photoStr, Some(new Date()), None)
      actorRef ! msg1

      // スピードを解決したPhotoMessageがパイプから流れ込んでくる
      val msg2 = PhotoMessage("id1", photoStr, None, Some(60))
      actorRef ! msg2

      // expected
      val combinedMsg = PhotoMessage("id1", photoStr, msg1.creationTime, msg2.speed)
      endProbe.expectMsg(combinedMsg)
    }

    // タイムアウト処理テスト
    "send message after timeout" in {
      val endProbe = TestProbe()
      val actorRef = system.actorOf(Props(new Aggregator(timeout, endProbe.ref)))
      val photoStr = ImageProcessing.createPhotoString(new Date(), 60)

      // 時刻を解決したPhotoMessageがパイプから流れ込んでくる
      val msg1 = PhotoMessage("id1", photoStr, Some(new Date()), None)
      actorRef ! msg1

      // タイムアウトまで待ち、ちゃんと送出されてくるはず
      endProbe.expectMsg(msg1)
    }

    "aggregate two messages when restarting" in {
      val endProbe = TestProbe()
      val actorRef = system.actorOf(Props(new Aggregator(timeout, endProbe.ref)))
      val photoStr = ImageProcessing.createPhotoString(new Date(), 60)

      // 時刻を解決したPhotoMessageがパイプから流れ込んでくる
      val msg1 = PhotoMessage("id1", photoStr, Some(new Date()), None)
      actorRef ! msg1

      // Aggregatorを無理やり再起動させる
      actorRef ! new IllegalStateException("Do restart!!!!!")

      // スピードを解決したPhotoMessageがパイプから流れ込んでくる
      val msg2 = PhotoMessage("id1", photoStr, None, Some(60))
      actorRef ! msg2

      val combinedMsg = PhotoMessage("id1", photoStr, msg1.creationTime, msg2.speed)
      endProbe.expectMsg(combinedMsg)
    }
  }
}
