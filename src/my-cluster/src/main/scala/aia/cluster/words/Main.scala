package aia.cluster
package words

import com.typesafe.config.ConfigFactory
import akka.actor.{Props, ActorSystem}
import akka.cluster.Cluster

import JobReceptionist.JobRequest

object Main extends App {
  val config = ConfigFactory.load()

  println(s"Starting node with roles: ${Cluster(system).selfRoles}")

  if (system.settings.config.getStringList("akka.cluster.roles").contains("master")) {
    // min-nr-of-membersに応じて、メンバーノードがUpになった時に実行される内容を定義
    Cluster(system).registerOnMemberUp {
      // JobRequestを受け取る受信者(Receptionist)アクターを定義
      val receptionist = system.actorOf(Props[JobReceptionist], "receptionist")
      println("Master node is ready.")

      // 処理対象のテキストのリスト
      val text = List(
        "This is a test",
        "of some very naive word counting",
        "but what can you say",
        "it is what it is"
      )

      // 受信者にJobRequestを投げつける
      receptionist ! JobRequest("the first job", (1 to 100000).flatMap( i =>
          text ++ text).toList
      )

      // デバッグ用に、クラスタドメインイベントリスナーアクターを生成
      system.actorOf(Props(new ClusterDomainEventListener), "cluster-listener")
    }
  }
}
