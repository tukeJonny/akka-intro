package aia.channels

import org.scalatest.{ WordSpecLike, BeforeAndAfterAll, MustMatchers }
import akka.testkit.ImplicitSender
import akka.actor.{ Props, Actor }

import akka.remote.testkit.{ MultiNodeSpecCallbacks, MultiNodeConfig, MultiNodeSpec }

trait STMultiNodeSpec extends MultiNodeSpecCallbacks
  with WordSpecLike
  with MustMatchers
  with BeforeAndAfterAll {

  override def beforeAll() = multiNodeSpecBeforeAll()
  override def afterAll() = multiNodeSpecAfterAll()
}

object ReliableProxySampleConfig extends MultiNodeConfig {
  val client = role("Client")
  val server = role("Server")

  // メッセージ配送失敗をシミュレーションする
  testTransport(on = true)
}

class ReliableProxySampleSpecMultiJvmNode1 extends ReliableProxySample
class ReliableProxySampleSpecMultiJvmNode2 extends ReliableProxySample



import akka.remote.transport.THrottlerTransportAdapter.Direction
import scala.concurrent.duration._
import concurrent.Await
import akka.contrib.pattern.ReliableProxy

class ReliableProxySample extends MultiNodeSpec(ReliableProxySampleConfig)
  with STMultiNodeSpec
  with ImplicitSender {

  import ReliableProxySampleConfig._

  def initialParticipants = roles.size

  "A MultiNodeSample" must {

    "wait for all nodes to enter a barrier" in {
      enterBarrier("startup")
    }

    "send to and receive from a remote node" in {
      // Client
      runOn(client) {
        enterBarrier("deployed")

        val pathToEcho = node(server) / "user" / "echo"
        val echo = system.actorSelection(pathToEcho)
        val proxy = system.actorOf(ReliableProxy.props(pathToEcho, 500.millis), "proxy")

        proxy ! "message1"
        expectMsg("message1")
        // testConductor.blackholeを利用して、
        // client - server 間の通信を１秒後に
        // 双方向の通信をブラックホールに吸い込まれるようにする
        Await.ready(
          testConductor.blackhole( // 通信を遮断 
            client, // クライアント、
            server, // サーバ間
            Direction.Both // 双方向の通信を
          ), 1 second // 1秒経過後に
        )

        echo ! "DirectMessage" // Echoサーバに直接メッセージを送信
        proxy ! "ProxyMessage" // ReliableProxyを介してEchoサーバにメッセージを送信
        expectNoMsg(3 seconds) // 3秒間メッセージが来ないことを確認

        // 1秒後にclient - server間の通信を通すようにする
        Await.ready(
          testConductor.passThrough(
            client,
            server,
            Direction.Both
          ), 1 second
        )

        // ReliableProxyがネットワーク回復後に送信してくれる
        // 直接送ったメッセージは来ない
        expectMsg("ProxyMessage")

        // ネットワーク回復後に直接送信することができるようになってる
        echo ! "DirectMessage2"
        expectMsg("DirectMessage2")
      }

      // Server
      runOn(server) {
        // そのまま同じメッセージを返答するEchoアクターを起動
        system.actorOf(Props(new Actor {
          def receive = {
            case msg: AnyRef => {
              sender() ! msg
            }
          }
        }), "echo")
        enterBarrier("deployed")
      }

      enterBarrier("finished")
    }
  }

}
