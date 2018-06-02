package com.goticks

import akka.actor._
import akka.actor.ActorIdentity
import akka.actor.Identify

import scala.concurrent.duration._

// becomeを使ってメッセージ受信の仕方を動的に変更する
class RemoteLookupProxy(path: String) extends Actor
                                      with ActorLogging {
  context.setReceiveTimeout(3 seconds)
  sendIdentifyRequest() // 起動直後にアクター識別リクエストを相手に送信（接続確立のためって感じかな）

  // 接続を確立するためにPATHで指定された宛先にIdentifyRequestを送信する
  def sendIdentifyRequest(): Unit = {
    val selection = context.actorSelection(path)
    selection ! Identify(path)
  }

  def receive = 
    identify

  // 接続の確立ができたら、メッセージを転送するactiveモードに移行する
  def identify: Receive = {
    // バッククウォートで書くことで、RemoteLookupProxy初期化時に渡されたpathと一致するパラメータ
    // が渡されないとマッチしないことを示す
    case ActorIdentity(`path`, Some(actor)) =>
      context.setReceiveTimeout(Duration.Undefined)
      log.info("[+] Switching to active state")
      context.become(active(actor))
      context.watch(actor) // Identifyされたアクターを監視

    case ActorIdentity(`path`, None) =>
      log.error(s"Remote actor with path $path is not available.")

    case ReceiveTimeout =>
      sendIdentifyRequest()

    case msg: Any =>
      log.error(s"Ignoring message $msg, remote actor is not ready yet.")
  }

  // 終了以外のメッセージを透過的に転送
  // もし終了してしまった場合は、宛先との接続を再確立するためにidentifyモードに移行する
  def active(actor: ActorRef): Receive = {
    case Terminated(actorRef) =>
      log.info("[!] Actor $actorRef terminated.")
      log.info("[+] Switching to identify state")
      context.become(identify)

      context.setReceiveTimeout(3 seconds)
      sendIdentifyRequest()

    case msg: Any =>
      actor forward msg
  }
}
