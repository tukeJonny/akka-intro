package aia.cluster
package words

import akka.testkit.{ImplicitSender, TestKit}
import akka.actor._

import org.scalatest._
import org.scalatest.MustMatchers

import JobReceptionist._
import akka.routing.BroadcastPool

// このトレイトをミックスインするには、contextを定義していなければならない
// ここではJobMasterがミックスインする
trait CreateLocalWorkerRouter extends CreateWorkerRouter { this: Actor =>
  override def createWorkerRouter: ActorRef = {
    // クラスタリングされてないルータを作成
    context.actorOf(BroadcastPool(5).props(Props[JobWorker]), "worker-router")
  }
}

class TestJobMaster extends JobMaster with CreateLocalWorkerRouter
class TestReceptionist extends JobReceptionist with CreateMaster {
  override def createMaster(name: String): ActorRef =
    context.actorOf(Props[TestJobMaster], name)
}

class LocalWordsSpec extends TestKit(ActorSystem("test"))
  with WordSpecLike
  with MustMatchers
  with StopSystemAfterAll
  with ImplicitSender {

  val receptionist = system.actorOf(Props[TestReceptionist], JobReceptionist.name)

  "The words system" must {
    // テキスト中単語出現回数を計算
    "count the occurenece of  words in a text" in {
      val text = List(
        "this is a test ",
        "this is a test",
        "this is",
        "this"
      )

      receptionist ! JobRequest("test2", text)
      expectMsg(JobSuccess("test2", Map(
        "this" -> 4,
        "is"   -> 3,
        "a"    -> 2,
        "test" -> 2
      )))
      expectNoMsg
    }

    // 単語を増やしてみる
    "count many occurence of words in a text" in {
      val text = List(
        "this is a test ",
        "this is a test",
        "this is",
        "this"
      )
      val words = (1 to 100).map( i =>
        text ++ text
      ).flatten.toList

      receptionist ! JobRequest("test3", words)
      expectMsg(JobSuccess("test3", Map(
        "this" -> 800,
        "is"   -> 600,
        "a"    -> 400,
        "test" -> 400
      )))
      expectNoMsg
    }

    // 障害を起こしても、ジョブが継続される
    "continue to process a job with intermittent failures" in {
      val text = List(
        "this",
        "is",
        "a",
        "test",
        "FAIL!"
      )

      receptionist ! JobRequest("test4", text)
      expectMsg(JobSuccess("test4", Map(
        "this" -> 1,
        "is"   -> 1,
        "a"    -> 1,
        "test" -> 1
      )))
      expectNoMsg
    }
  }
}
