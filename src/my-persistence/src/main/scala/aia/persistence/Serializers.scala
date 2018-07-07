package aia.persistence

import akka.serialization._
import spray.json._

class BasketEventSerializer extends Serializer {
  import JsonFormats._

  val includeManifest: Boolean = false // manifestは必要ない
  val identifier = 123678213 // シリアライザごとにUniqueなID

  def toBinary(obj: AnyRef): Array[Byte] = {
    obj match {
      case e: Basket.Event => // Basketイベントのみシリアライズ
        BasketEventFormat.write(e).compactPrint.getBytes
      case msg =>
        throw new Exception(s"Cannot serialize $msg with ${this.getClass}")
    }
  }

  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    // バイト列をspray-jsonのASTに変換
    val jsonAst = new String(bytes).parseJson
    // BasketEventFormatを用いて json -> Basket.Eventに変換
    BasketEventFormat.read(jsonAst)
  }
}

class BasketSnapshotSerializer extends Serializer {
  import JsonFormats._

  val includeManifest: Boolean = false
  val identifier = 1242134234

  def toBinary(obj: AnyRef): Array[Byte] = {
    obj match {
      case snap: Basket.Snapshot =>
        snap.toJson.compactPrint.getBytes
      case msg =>
        throw new Exception(s"Cannot serialize $msg")
    }
  }

  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val jsonStr = new String(bytes)
    jsonStr.parseJson.convertTo[Basket.Snapshot]
  }
}

// spray-jsonフォーマットが含まれている
// BasketEventFormatも含まれていて
object JsonFormats extends DefaultJsonProtocol {
  implicit val itemFormat: RootJsonFormat[item] = jsonFormat3(Item)
  implicit val itemsFormat: RootJsonFormat[Items] = jsonFormat(
    (list: List[Item]) =>
      Items.aggregate(list), "items"
  )
  implicit val addedEventFormat: RootJsonFormat[Basket.Added] = jsonFormat1(Basket.Added)
  implicit val removedEventFormat: RootJsonFormat[Basket.ItemRemoved] = jsonFormat1(Basket.ItemRemoved)
  implicit val updateEventFormat: RootJsonFormat[Basket.ItemUpdated] = jsonFormat2(Basket.ItemUpdated)
  implicit val replacedEventFOrmat: RootJsonFormat[Basket.Replaced] = jsonFormat1(Basket.Replaced)
  implicit val clearedEventFormat: RootJsonFormat[Basket.Cleared] = jsonFormat1(Basket.Cleared)
  implicit val snapshotEventFormat: RootJsonFormat[Basket.Snapshot] = jsonFormat1(Basket.Snapshot)

  implicit object BasketEventFormat extends RootJsonFormat[Basket.Event] {
    import Basket._
    val addedId = JsNumber(1)
    val removedId = JsNumber(2)
    val updatedId = JsNumber(3)
    val replaceId = JsNumber(4)
    val clearedId = JsNumber(5)

    def write(event: Event) = {
      event match {
        case e: Added =>
          JsArray(addedId, addedEventFormat.write(e))
        case e: ItemRemoved =>
          JsArray(removedId, removedEventFormat.write(e))
        case e: ItemUpdated =>
          JsArray(updatedId, updatedEventFormat.write(e))
        case e: Replaced =>
          JsArray(replacedId, replacedEventFormat.write(e))
        case e: Cleared =>
          JsArray(clearedId, clearedEventFormat.write(e))
      }
    }

    def read(json: JsValue): Basket.Event = {
      json match {
        case JsArray(Vector(`addedId`, jsEvent)) =>
          addedEventFormat.read(jsEvent)
        case JsArray(Vector(`removedId`, jsEvent)) =>
          removedEventFormat.read(jsEvent)
        case JsArray(Vector(`updatedId`, jsEvent)) =>
          updatedEventFormat.read(jsEvent)
        case JsArray(Vector(`replacedId`, jsEvent)) =>
          replacedEventFormat.read(jsEvent)
        case JsArray(Vector(`clearedId`, jsEvent)) =>
          clearedEventFormat.read(jsEvent)
        case j =>
          deserializationError("Expected basket event, but got " + j)
      }
    }
  }
}
