package com.goticks

import scala.concurrent.duration._
import akka.remote.testkit.MultiNodeSpec
import akka.util.Timeout

import akka.testkit.ImplicitSender
import akka.actor._
import TicketSeller._

// sbtで
// multi-jvm:test
// を実行すれば、Multi JVMテストできる

class ClientServerSpecMultiJvmFrontend extends ClientServerSpec
class ClientServerSpecMultiJvmBackend extends ClientServerSpec


// ImplicitSenderトレイトは全メッセージのデフォルトのsenderとしてtestActorを設定してくれる
class ClientServerSpec extends MultiNodeSpec(ClientServerConfig)
    with STMultiNodeSpec with ImplicitSender {
  import ClientServerConfig._

  // node(<role>) メソッドは、テスト中バックエンドロールノードのアドレスを返す
  // このメソッドはメインテストスレッドから呼び出されなくてはいけないという制約がある
  val backendNode = node(backend)

  def initialParticipants =
    role.size

  "A Client Server configured app" must {
    "wait for all nodes to enter a barrier" in {
      enterBarrier("startup") // 全てのノードが開始するのを待つ
    }

    "be able to create an event and sell a ticket" in {
      // このブロックのコードをバックエンドJVMで実行するようにする
      runOn(backend) {
        system.actorOf(BoxOffice.props(Timeout(1 second)), "boxOffice")
        enterBarrier("deployed") // バックエンドがデプロイされたシグナル
      }

      // このブロックのコードをフロントエンドJVMで実行できるようにする
      runOn(frontend) {
        enterBarrier("deployed") // バックエンドノードがデプロイされるまで待機

        // backendのactorSelection(akka-remoteの場合のactorRef）を取得する
        val path = node(backend) / "user" / "boxOffice"
        val actorSelection = system.actorSelection(path)

        actorSelection.tell(Identify(path), testActor) // Identifyメッセージをバックエンドに送信

        // ActorIdentityが帰ってきたら、ActorRefを保持しておく
        val actorRef = expectMsgPF() {
          case ActorIdentity(`path`, Some(ref)) =>
            ref
        }

        import BoxOffice._

        // RHCPイベントを20000枚チケットで作成
        actorRef ! CreateEvent("RHCP", 20000)
        expectMsg(EventCreated(Event("RHCP", 20000)))

        // RHCPイベントのチケットを1枚取得
        actorRef ! GetTickets("RHCP", 1)
        expectMsg(Tickets("RHCP", Vector(Ticket(1))))
      }

      enterBarrier("finished") // テストを完了
    }
  }
}
