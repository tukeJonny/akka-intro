package aia.structure

import scala.concurrent.duration._

import akka.actor._

import org.scalatest._
import akka.testkit._
import scala.language.postfixOps

class PipeAndFilterTest extends TestKit(ActorSystem("PipeAndFilterTest"))
  with WordSpecLike
  with BeforeAndAfterAll {
  val timeout = 2 seconds

  override def afterAll(): Unit = {
    system.terminate()
  }

  "The pipe and filter" must {

    // licenseFilter -> speedFilter -> endProbe
    "filter messages in configuration 1" in {
      val endProbe = TestProbe() // 最後にテストで受け取るためのProbe（パイプラインの末尾にある)
      val speedFilterRef = system.actorOf(Props(new SpeedFilter(50, endProbe.ref))) // 次に速度チェック
      val licenseFilterRef = system.actorOf(Props(new LicenseFilter(speedFilterRef))) // 最初にナンバープレートチェック

      val msg = new Photo("123xyz", 60)
      licenseFilterRef ! msg
      endProbe.expectMsg(msg) // 最終的なナンバープレートは同じはず

      licenseFilterRef ! new Photo("", 60) // ナンバープレートがない場合
      endProbe.expectNoMessage(timeout) // 出力されない

      licenseFilterRef ! new Photo("123xyz", 49) // 速度が最低速度に満たない場合
      endProbe.expectNoMessage(timeout) // 出力されない
    }

    // speedFilter -> licenseFilter -> endProbe
    "filter messages in configuration 2" in {
      val endProbe = TestProbe()
      val licenseFilterRef = system.actorOf(Props(new LicenseFilter(endProbe.ref)))
      val speedFilterRef = system.actorOf(Props(new SpeedFilter(50, licenseFilterRef)))

      val msg = new Photo("123xyz", 60)
      speedFilterRef ! msg
      endProbe.expectMsg(msg)

      speedFilterRef ! new Photo("", 60)
      endProbe.expectNoMessage(timeout)

      speedFilterRef ! new Photo("123xyz", 49)
      endProbe.expectNoMessage(timeout)
    }
  }
}
