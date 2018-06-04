package aia.routing

import akka.actor._
import org.scalatest._
import akka.testkit._

class RouteSlipTest extends TestKit(ActorSystem("RouteSlipTest"))
  with WordSpecLike
  with BeforeAndAfterAll {

  override def afterAll(): Unit = {
    system.terminate()
  }

  "The Router" must {
    "route messages correctly" in {
      val probe = TestProbe()
      val router = system.actorOf(Props(new SlipRouter(probe.ref)), "SlipRouter")

      // 欲のない注文にはデフォの車が返ってくるはず
      val minimalOrder = new Order(Seq())
      router ! minimalOrder
      val defaultCar = new Car(
        color = "black",
        hasNavigation = false,
        hasParkingSensors = false)
      probe.expectMsg(defaultCar)

      // 欲張りな全部セット注文には、諸々ついた結果が返ってくるはず
      val fullOrder = new Order(Seq(
        CarOptions.CAR_COLOR_GRAY,
        CarOptions.NAVIGATION,
        CarOptions.PARKING_SENSORS))
      router ! fullOrder
      val carWithAllOptions = new Car(
        color = "gray",
        hasNavigation = true,
        hasParkingSensors = true)
      probe.expectMsg(carWithAllOptions)
    }
  }
}
