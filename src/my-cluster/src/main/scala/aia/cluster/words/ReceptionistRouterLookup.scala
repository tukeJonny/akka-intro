package aia.cluster
package words

import akka.actor._
import akka.cluster.routing._
import akka.routing._

trait ReceptionistRouterLookup { this: Actor =>
  def receptionistRouter = {
    val settings = ClusterRouterGroupSettings(
      totalInstances = 100,
      routeesPaths = List("/user/receptionist"),
      allowLocalRoutees = true,
      useRole = Some("master") // マスターノードにだけルーティング
    )

    // Receptionistを探し出すRouterを作成
    context.actorOf(ClusterRouterGroup(
      BroadcastGroup(Nil),
      settings
    ).props(), name="receptionist-router")
  }
}
