
package aia.routing

import scala.concurrent.duration._
import akka.actor._
import org.scalatest._

import akka.routing._
import akka.routing.ConsistentHashingRouter._

import akka.testkit.{ TestProbe, TestKit }

class HashRoutingTest extends TestKit(ActorSystem("MsgRoutingTest"))
    with WordSpecLike
    with BeforeAndAfterAll {
  override def afterAll() = {
    system.terminate()
  }

  "The Router" must {
    "routes depending on speed" in {
      val endProbe = TestProbe()

      // Hashルーターに使わせる部分関数を定義
      def hashMapping: ConsistentHashMapping = {
        case msg: GatherMessage =>
          msg.id
      }

      val router = system.actorOf(
        ConsistentHashingPool(10,
          virtualNodesFactor = 10,
          hashMapping = hashMapping).
        props(Props(new SimpleGather(endProbe.ref)))
      )

      router ! GatherMessageNormalImpl("1", Seq("msg1"))
      endProbe.expectNoMsg(100.millis)

      router ! GatherMessageNormalImpl("1", Seq("msg2"))
      endProbe.expectMsg(GatherMessageNormalImpl("1", Seq("msg1", "msg2")))

      router ! GatherMessageNormalImpl("10", Seq("msg1"))
      endProbe.expectNoMsg(100.millis)

      router ! GatherMessageNormalImpl("10", Seq("msg2"))
      endProbe.expectMsg(GatherMessageNormalImpl("10", Seq("msg1", "msg2")))

      system.stop(router)
    }

    "routes direct test" in {
      val endProbe = TestProbe()

      val router = system.actorOf(
        ConsistentHashingPool(10, virtualNodesFactor = 10).
        props(Props(new SimpleGather(endProbe.ref))), name = "routerMessage"
      )

      router ! ConsistentHashableEnvelope(
        message = GatherMessageNormalImpl("1", Seq("msg1")),
        hashKey = "someHash")
    }
  }
}
