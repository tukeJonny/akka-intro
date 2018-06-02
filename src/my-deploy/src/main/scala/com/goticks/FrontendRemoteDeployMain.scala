package com.goticks

import akka.actor.{ ActorRef, ActorSystem }
import akka.event.Logging
import com.typesafe.config.ConfigFactory

object FrontendRemoteDeployMain extends App
    with Startup {
  val config = ConfigFactory.load("frontend-remote-deploy")
  implicit val system = ActorSystem("frontend", config)

  // RestApiはトレイトなのでインスタンス化できない
  // そのため、即時クラスを作成し、RestApiトレイトを継承したそのクラスの
  // インスタンスを作成するのがこの構文
  val api = new RestApi() {
    val log = Logging(system.eventStream, "frontend-remote")
    implicit val requestTimeout = configuredRequestTimeout(config)
    implicit def executionContext = system.dispatcher

    def createBoxOffice: ActorRef =
      system.actorOf(BoxOffice.props, BoxOffice.name)
  }

  startup(api.routes)
}
