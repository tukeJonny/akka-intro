package aia.cluster.words

import akka.actor.{ActorLogging, Actor}

import akka.cluster.{MemberStatus, Cluster}
import akka.cluster.ClusterEvent._

class ClusterDomainEventListener extends Actor
  with ActorLogging {

  // アクター作成時, クラスタードメインイベントをサブスクライブ
  // これにより、クラスタードメインイベントを受信し、subscribeできるようになる
  Cluster(context.system).subscribe(self, classOf[ClusterDomainEvent])

  // クラスタードメインイベントを受信した際の振る舞い
  def receive = {
    case MemberUp(member) =>
      log.info(s"$member起動")
    case MemberExited(member) =>
      log.info(s"$member終了")
    case MemberRemoved(member, previousState) =>
      if (previousState == MemberStatus.Exiting)
        log.info(s"$memberは以前グレースフルに終了しています.クラスタから削除.")
      else
        log.info(s"$memberは到達不能になってからダウンしました.クラスタから削除.")
    case UnreachableMember(member) =>
      log.info(s"$memberは到達不能になりました")
    case ReachableMember(member) =>
      log.info(s"$memberは到達可能になりました")
    case state: CurrentClusterState =>
      log.info(s"クラスターの現在の状態: $state")
  }

  // ClusterDomainEventListenerアクター停止時、
  // クラスタードメインイベントをアンサブスクライブしておく
  override def postStop(): Unit = {
    Cluster(context.system).unsubscribe(self)
    super.postStop()
  }
}
