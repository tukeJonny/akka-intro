package aia.integration

import akka.actor.Props

// responseAs[NodeSeq] 動作に必要なXMLをサポートする暗黙モジュールをインポート
// これにより、ToEntityMarshallerとFromEntityUnmarshallerがインポートされ、
// akka-httpに暗黙的に渡されることでscala.xml.NodeSeqからレスポンスを完成させる方法を
// 知ることができる
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.{ Matchers, WordSpec }

import scala.concurrent.duration._
import scala.xml.NodeSeq

class OrderServiceTest extends WordSpec
  with Matchers
  with OrderService
  // ルートをテストする用
  with ScalatestRouteTest {

  val processOrders = system.actorOf(Props(new ProcessOrders), "order")

  implicit val executionContext = system.dispatcher
  implicit val requestTimeout = akka.util.Timeout(1 second)

  "The order service" should {

    "return NotFound if the order cannot be found" in {
      // 存在しない注文の取得を試みた場合、404 NotFoundが返ってくるはず
      Get("/orders/1") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

    "return the trakcing order for an order that was posted" in {
      val xmlOrder =
        <order>
          <customerId>customer1</customerId>
          <productId>Akka in action</productId>
          <number>10</number>
        </order>

      // XMLから注文を作成
      Post("/orders", xmlOrder) ~> routes ~> check {
        status shouldEqual StatusCodes.OK // 作成できるはず

        val xml = responseAs[NodeSeq]
        (xml \\ "id").text.toInt shouldEqual 1
        (xml \\ "status").text shouldEqual "received"
      }

      Get("/orders/1") ~> routes ~> check {
        status shouldEqual StatusCodes.OK // あるはず

        val xml = responseAs[NodeSeq]
        (xml \\ "id").text.toInt shouldEqual 1
        (xml \\ "status").text shouldEqual "processing"
      }
    }

  }
}
