package aia.deploy

import akka.actor.{ ActorRef, ActorLogging, Actor }
import scala.concurrent.duration._

class HelloWorld extends Actor with ActorLogging {
  def receive = {
    case msg: String =>
      val hello = "Hello %s".format(msg)
      sender() ! hello
      log.info("[+] Sent Response {}", hello)
  }
}

// HelloWorldを一定時間感覚で起動(メッセージを送ることにより)
class HelloWorldCaller(timer: FiniteDuration, actor: ActorRef) extends Actor
  with ActorLogging {
  case class TimerTick(msg: String)

  // アクター起動時にTimerTickメッセージを自分自身に送信するようにスケジュール
  override def preStart(): Unit = {
    super.preStart()

    implicit val ec = context.dispatcher
    // schedule( <発火時間>, <インターバル>, <送信先ActorRef>, <送信メッセージ> )
    context.system.scheduler.schedule(timer, timer, self, new TimerTick("everybody"))
  }

  // TimerTickメッセージを受診したら、対象のアクターにメッセージを送る
  def receive = {
    case msg: String =>
      log.info("[+] received {}", msg)
    case tick: TimerTick =>
      actor ! tick.msg
  }
}
