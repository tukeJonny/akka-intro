package aia.faulttolerance

import akka.actor._
import akka.actor.Terminated

/**
 * dbWriterを監視し、Terminatedへ状態が行こうした場合にWarningログを書き出す
 * context.watchすることで、これを実行したアクターにメッセージが来るため、
 * DbWatcherがcontext.watch(dbWriter)することでdbWriterが状態変更した場合に
 * メッセージがDbWatcherへ飛んで来て、receiveメソッドで分岐して処理を行う
 * みたいな流れ
 */
object DbStrategy2 {
  class DbWatcher(dbWriter: ActorRef) extends Actor with ActorLogging {
    context.watch(dbWriter)

    def receive = {
      case Terminated(actorRef) =>
        log.warning("Actor {} terminated", actorRef)
    }
  }
}
