package aia.persistence

case class Item(productId: String, number: Int, unitPrice: BigDecimal) {
  // 商品番号が一致するアイテムであれば、ナンバーを足し入れる
  def aggregate(item: Item): Option[Item] = {
    if (item.productId == productId) {
      Some(copy(number = number + item.number))
    } else {
      None
    }
  }

  def update(number: Int): Item =
    copy(number = number)
}

object Items {
  // initialize
  def apply(args: Item*): Items =
    Items.aggregate(args.toList)

  def aggregate(list: List[Item]): Items =
    Items(add(list))

  private def add(list: List[Item]) =
    aggregateIndexed(indexed(list))
  private def indexed(list: List[Item]) =
    list.zipWithIndex

  private def aggregateIndexed(indexed: List[(Item, Int)]) = {
    // productIdでグループ化する
    def grouped = indexed.groupBy {
      case (item, _) => item.productId
    }

    def reduced = grouped.flatMap { 
      case (_, groupedIndexed) => {
        val init = (Option.empty[Item], Int.MaxValue)
        val (item, ix) = groupedIndexed.foldLeft(init) {
          case ((accItem, accIx), (item, ix)) => {
            // accItemの各要素とitemを集約し、aggregatedとする
            // 畳み込みで最終的にはaccItemとitemが結合されることになる
            // sndについては、ixの最小値を保持するようにする
            val aggregated = accItem.map(i => item.aggregate(i)).getOrElse(Some(item))
            (aggregated, Math.min(accIx, ix))
          }
        }
        // 0よりも大きい数を持つItemにフィルタし、最小値とあわせてタプルで返す
        item.filter(_.number > 0).map(i => (i, ix))
      }
    }

    // indexでソートしてitemのみ取り出す
    def sorted = 
      reduced.toList
             .sortBy { case (_, index) => index }
             .map    { case (item, _)  => item  }

    // sorted -> reduced -> ... とsortedを起点に呼び出す
    sorted
  }
}

case class Items(list: List[Item]) {
  import Items._

  // アイテムを追加
  def add(newItem: Item) =
    Items.aggregate(list :+ newItem)
  // アイテムのリストと結合
  def add(items: Items) =
    Items.aggregate(list ++ items.list)

  // 指定したproductIdのItemが存在するか
  def containsProduct(productId: String) =
    list.exists(_.productId == productId)

  // 指定したproductIdを削除したリストにする
  def removeItem(productId: String) =
    Items.aggregate(list.filterNot(_.productId == productId))

  // 指定したproductIdのnumberを更新する
  // 更新できなかった場合は、更新前リストで扱う
  def updateItem(productId: String, number: Int) ={
    val newList = list.find(_.productId == productId).map { item =>
      list.filterNot(_.productId == productId) :+ item.update(number)
    }.getOrElse(list)
    Items.aggregate(newList)
  }

  // からのItemsを作成
  def clear =
    Items()
}

