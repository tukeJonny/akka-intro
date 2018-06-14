package aia.stream

import java.io.File
import java.time.ZonedDateTime
import scala.concurrent.Future
import akka.NotUsed
import akka.util.ByteString
import akka.stream.IOResult
import akka.stream.scaladsl.{ Source, FileIO, Framing }

import scala.concurrent.duration.FiniteDuration

// ログの１行にある情報を示す
case class Event(host: String, service: String, state: State,
                time: ZonedDateTime, description: String,
                tag: Option[String] = None,
                metric: Option[Double] = None)

// Stateトレイとは、Eventからのみ見ているため、sealedで良い。
sealed trait State
case object Critical extends State
case object Error extends State
case object Ok extends State
case object Warning extends State

object State {
  // StringやStateを、小文字のStringに変換する
  def norm(str: String): String =
    str.toLowerCase
  def norm(state: State): String =
    norm(state.toString)

  // ログレベル
  val ok = norm(Ok)
  val warning = norm(Warning)
  val error = norm(Error)
  val critical = norm(Critical)

  // ログレベルを抽出
  def unapply(str: String): Option[State] = {
    val normalized = norm(str)

    if (normalized == norm(Ok)) Some(Ok)
    else if (normalized == norm(Warning)) Some(Warning)
    else if (normalized == norm(Error)) Some(Error)
    else if (normalized == norm(Critical)) Some(Critical)
    else None
  }
}

case class LogReceipt(logId: String, written: Long)
case class ParseError(logId: String, msg: String)
