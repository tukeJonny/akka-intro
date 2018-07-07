package aia.persistence.calculator

import akka.actor._
import akka.testkit._
import org.scalatest._

class CalculatorSpec extends PersistenceSpec(ActorSystem("test"))
  with PersistenceCleanup {

  "The Calculator" should {
    // クラッシュ後に最後の正常状態に戻るはず
    "recover last known result after crash" in {
      val calc = system.actorOf(Calculator.props, Calculator.name)

      // 0 + 1d = 1d
      calc ! Calculator.Add(1d)
      calc ! Calculator.GetResult
      expectMsg(1d)

      // 0 + 0.5d = 0.5d
      calc ! Calculator.Subtract(0.5d)
      calc ! Calculator.GetResult
      expectMsg(0.5d)

      // 殺して再立ち上げしたら、最後の0.5が記録されてるはず
      killActors(calc)
      val calcResurrected = system.actorOf(Calculator.props, Calculator.name)
      calcResurrected ! Calculator.GetResult
      expectMsg(0.5d)

      // 0.5d + 1.0d = 1.5d
      calcResurrected ! Calculator.Add(1d)
      calcResurrected ! Calculator.GetResult
      expectMsg(1.5d)
    }
  }
}
