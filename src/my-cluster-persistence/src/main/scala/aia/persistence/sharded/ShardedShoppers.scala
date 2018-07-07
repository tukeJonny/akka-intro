package aia.persistence.sharded

import aia.persistence._
import akka.actor._
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}

object ShardedShoppers {
  def props =
    Props(new ShardedShoppers)
  def name =
    "sharded-shoppers"
}

class ShardedShoppers extends Actor {
  // シャードに分けられるアクターはentityとも呼ばれる
  ClusterSharding(context.system).start(
    ShardedShopper.shardName, // typeName(シャードに分けられるアクター種別を表す名前)
    ShardedShopper.props, // entityProps(これを用いて、適切にShardedShopperが自動起動される
    ClusterShardingSettings(context.system),
    ShardedShopper.extractEntityId,
    ShardedShopper.extractShardId
  )

  def shardedShopper = {
    ClusterSharding(context.system).shardRegion(ShardedShopper.shardName)
  }

  def receive = {
    case cmd: Shopper.Command => shardedShopper forward cmd
  }
}
