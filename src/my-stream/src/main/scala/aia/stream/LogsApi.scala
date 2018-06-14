package aia.stream

import java.nio.file.{ Files, Path, Paths }
import java.nio.file.StandardOpenOption
import java.nio.file.StandardOpenOption._

import java.time.ZonedDateTime

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.{ Success, Failure }

import akka.Done
import akka.actor
import akka.util.ByteString

import akka.stream.{ ActorAttributes, ActorMaterializer, IOResult }
import akka.stream.scaladsl.{ FileIO, BidiFlow, Flow, Framing, Keep, Sink, Source }
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import spray.json._

class LogApi(val logsDir: Path, val maxLine: Int)(
  implicit val executionContext: ExecutionContext,
  val materializer: ActorMaterializer
) extends EventMarshalling {

  // idに応じたログファイルを解決
  def logFile(id: String) =
    logsDir.resolve(id)

  val inFlow = Framing.delimiter(ByteString("\n"), maxLine)
    .map(_.decodeString("UTF8"))
    .map(LogStreamProcessor.parseLineEx)
    .collect { case Some(ev) => ev }

  val outFlow = Flow[Event].map { event =>
    ByteString(event.toJson.compactPrint)
  }

  val bidiFlow = BidiFlow.fromFlows(inFlow, outFlow)

  import java.nio.file.StandardOpenOption
  import java.nio.file.StandardOpenOption._

  val logToJsonFlow = bidiFlow.join(Flow[Event])

  def logFileSink(logId: String) =
    FileIO.toPath(logFile(logId), Set(CREATE, WRITE, APPEND))
  def logFileSource(logId: String) =
    FileIO.fromPath(logFile(logId))

  // 下に定義するルーティングを
  // POST -> GET -> DELETEの順でマッチングする
  def routes: Route =
    postRoute ~ getRoute ~ deleteRoute

  def postRoute =
    pathPrefix("logs" / Segment) { logId =>
      pathEndOrSingleSlash {
        post {
          entity(as[HttpEntity]) { entity =>
            onComplete(
              entity
                .dataBytes
                .via(logToJsonFlow)
                .toMat(logFileSink(logId))(Keep.right)
                .run()
              ) {
                // 成功した
                case Success(IOResult(count, Success(Done))) =>
                  complete((StatusCodes.OK, LogReceipt(logId, count)))
                // IOに失敗した
                case Success(IOResult(count, Failure(err))) =>
                  complete((
                    StatusCodes.BadRequest,
                    ParseError(logId, err.getMessage)
                  ))
                // IOする前に失敗した
                case Failure(err) =>
                  complete((
                    StatusCodes.BadRequest,
                    ParseError(logId, err.getMessage)
                  ))
              }
          }
        }
      }
    }

  // ログファイルが存在すればjsonで返し、なければNotFound
  def getRoute =
    pathPrefix("logs" / Segment) { logId =>
      pathEndOrSingleSlash {
        get {
          if (Files.exists(logFile(logId))) {
            val src = logFileSource(logId)
            complete(HttpEntity(ContentTypes.`application/json`, src))
          } else {
            complete(StatusCodes.NotFound)
        }
      }
    }

  // 存在すれば削除、なければInternalServerError
  def deleteRoute =
    pathPrefix("logs" / Segment) { logId => 
      pathEndOrSingleSlash {
        delete {
          if (Files.deleteIfExists(logFile(logId)))
            complete(StatusCodes.OK)
          else
            complte(StatusCodes.InternalServerError)
        }
      }
    }
}
