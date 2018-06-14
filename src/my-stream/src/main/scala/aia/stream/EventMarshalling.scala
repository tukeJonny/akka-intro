package aia.stream
import java.time.ZonedDateTime
import java.time.format.{ DateTimeFormatter, DateTimeParseException }

import scala.util.Try
import spray.json._

// spray.jsonを用いて、EventクラスをJSONにマーシャリングする

trait EventMarshalling extends DefaultJsonProtocol {
  // 時刻をしリアライズ・デシリアライズする
  implicit val dateTimeFormat = new JsonFormat[ZonedDateTime] {
    def write(dateTime: ZonedDateTime) =
      JsString(dateTime.format(DateTimeFormatter.ISO_INSTANT))

    def read(value: JsValue) =
      value match {
        case JsString(str) =>
          try {
            ZonedDateTime.parse(str)
          } catch {
            case e: DateTimeParseException =>
              val msg = s"Could not deserialize $str to ZonedDateTime"
              deserializationError(msg)
          }
        case js =>
          val msg = s"Could not deserialize $str to ZonedDateTime"
          deserializationError(msg)
      }
  }

  // 状態をシリアライズ・デシリアライズする
  implicit val stateFormat = new JsonFOrmat[State] {
    def write(state: State) =
      JsString(State.norm(state))

    def read(value: JsValue) =
      value match {
        case JsString("ok") => Ok
        case JsString("warning") => Warning
        case JsString("error") => Error
        case JsString("critical") => Critical
        case js =>
          val msg = s"Could not deserialize $js to State."
          deserializationError(msg)
      }
  }

  /**
   * 普通の文字列として扱えるもの
   * JsonFormatN の Nは引数の数
   * jsonFormat7(Event) であれば、
   * Event(arg1, arg2, ... , arg7) という感じ
   */

  // 
  implicit val eventFormat = jsonFormat7(Event)

  implicit val logIdFormat = jsonFormat2(LogReceipt)

  implicit val errorFormat = jsonFormat2(ParseError)
}
