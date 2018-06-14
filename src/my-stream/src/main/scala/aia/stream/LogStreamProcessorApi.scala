package aia.stream

import java.nio.file.{ Files, Path, Paths }
import java.nio.file.StandardOpenOption
import java.nio.file.StandardOpenOption._

import java.time.ZonedDateTime

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.{ Success, Failure }

import akka.{ Done, NotUsed }
import akka.actor._
import akka.util.ByteString

import akka.stream.{ ActorAttributes, ActorMaterializer, IOResult }
import akka.stream.scaladsl.{ FileIO, BidiFlow, Flow, Framing, Keep, Sink, Source }

import akka.http.scaladsl.common.EntityStreamingSupport
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.Marshall
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import spray.json._

class LogStreamProcessorApi(
  val logsDir: Path,
  val notificationsDir: Path,
  val metricsDir: Path,
  val maxLine: Int,
  val maxJsObject: Int
)(
  implicit val executionContext: ExecutionContext,
  val materializer: ActorMaterializer
) extends EventMarshalling {

  def logFile(id: String) =
    logsDir.resolve(id)

  /**
   * notificationとmetricsのSinkを作成する
   */
  val notificationSink = FileIO.toPath(notificationsDir.resolve("notifications.json"), Set(CREATE, WRITE, APPEND))
  val metricsSink = FileIO.toPath(metricsDir.resolve("metrics.json"), Set(CREATE, WRITE, APPEND))

  // バッファがオーバーフローした際のストラテジ関連もインポートする(OverFlowStrategy)
  import akka.sream.{ FlowShape, Graph, OverFlowStrategy }
  // 優先順位をつけられるマージするやつをインポートする(MergePreferred)
  import akka.sream.scaladsl.{ Broadcast, GraphDSL, MergePreferred, RunnableGraph }

  type FlowLike = Graph[FlowShape[Event, ByteString], NotUsed]

  def processEvents(logId: String): FlowLike = {
    val jsFlow = LogJson.jsonOutFlow
    val notifyOutFlow = LogJson.notifyOutFlow
    val metricOutFlow = LogJson.metricOutFlow
    Flow.fromGraph(
      GraphDSL.create() { implicit builder => 
        import GraphDSL.Implicits._ 

      /**
       * 設定項目を定義
       */
      val nrWarnings = 100
      val nrErrors = 10

      val archBufSize = 100000
      val warnBufSize = 100
      val errBufSize = 1000

      val errDuration = 10 seconds
      val warnDuration = 1 minute

      // イベントのフローからメトリクスを抽出する
      val toMetric = Flow[Event].collect {
        case Event(_, service, _, time, _, Some(tag), Some(metric)) =>
          Metric(service, time, metric, tag)
      }

      // 収集したメトリクスについて、0オリジンのdriftを設定する
      // driftは監視サービスが消費できる要素数を表している
      // つまらずに処理できている場合はIteratorが１つしか見ないのでdrift=0となるが、
      // 数が増えるに従ってdriftの数は大きくなっていく 
      val recordDrift = Flow[Metric].expand { metric =>
        Iterator.from(0).map(d => metric.copy(drift = d))
      }


        val bcast = builder.add(Broadcast[Event](5)) 
        // Warning, Err, Critical, Ok Bufferのブロードキャストも用意
        val wbcast = builder.add(Broadcast[Event](2))
        val ebcast = builder.add(Broadcast[Event](2))
        val cbcast = builder.add(Broadcast[Event](2))
        val okcast = builder.add(Broadcast[Event](2))

        // 通知マージ用、アーカイブ用のフローも作成する
        val mergeNotify = builder.add(MergePreferred[Summary](2))
        val archive = builder.add(jsFlow)

        /**
         * 速度差がでてバックプレッシャーがかかり、パフォーマンスが低下する場合、
         * 間にバッファをもうけてある程度溜まってから遅い処理を行うようなバッファリングによる速度の分離が
         * 基本的な方針である
         *
         * toNot,
         * rollupXXX,
         * recordDrift,
         * などのメソッドは、バッファリングのために用いている
         */

        // 致命的なイベントのエラーも表現するためのフロー
        // すべての通知メッセージをSummaryにして送信するために、致命的なイベントはEvent１つのSummaryとして送信する
        val toNot = Flow[Event].map(e => Summary(Vector(e)))

        val ok = Flow[Event].filter(_.state == Ok)
        val warning = Flow[Event].filter(_.state == Warning)
        val error = Flow[Event].filter(_.state == Error)
        val critical = Flow[Event].filter(_.state == Critical)

        // nrだけのグループについて、duration感覚でグルーピングを行って作成する
        // groupedWithinはList[Event]を返す
        def rollup(nr: Int, duration: FiniteDuration) =
          Flow[Event].groupedWithin(nr, duration).map(events => Summary(events.toVector))

        // Err, Warningを巻き上げる
        val rollupErr = rollup(nrErrors, errDuration)
        val rollupWarn = rollup(nrWarnings, warnDuration)

        // アーカイブのバッファ
        val archBuf = Flow[Event].buffer(archBufSize, OverflowStrategy.fail) // オーバーフローしたら失敗戦略
        // Warningのバッファ
        val warnBuf = Flow[Event].buffer(warnBufSize, OverflowStrategy.dropHead) // 古いログから消す戦略
        // Errorのバッファ
        val errBuf  = Flow[Event].buffer(errBufSize, OverflowStrategy.backpressure) // バックプレッシャーをかける戦略
        // メトリクスのバッファ
        val metricBuf = Flow[Event].buffer(errBufSize, OverflowStrategy.dropHead) // 古いメトリクスから消す戦略

        // broadcast -> [archive, ok|warning|error|critical]
        bcast ~> archBuf ~> archive.in
        bcast ~> ok ~> okcast
        bcast ~> warning ~> wbcast
        bcast ~> error ~> ebcast
        bcast ~> critical ~> cbcast

        // okcast -> [logFile, metrics]
        okcast ~> jsFlow -> logFileSink(logId, Ok)
        okcast ~> metricBuf ~> toMetric ~> recordDrift ~> metricOutFlow ~> metricsSink

        // cbcast -> [logFile, not & merge]
        cbcast ~> jsFlow -> logFileSink(logId, Critical)
        cbcast ~> toNot ~> mergeNotify.preferred // 致命的なエラーを優先的にマージする

        // ebcast -> [logFile, notify]
        ebcast ~> jsFlow ~> logFileSink(logId, Error)
        ebcast ~>errBuf ~> rollupErr ~> mergeNotify.in(0)

        // wbcast ~> [logFile, notify]
        wbcast ~> jsFlow ~> logFileSink(logId, Warning)
        wbcast ~> warnBuf ~> rollupWarn ~> mergeNotify.in(1)

        // merged err & warning -> notifySink
        mergeNotify ~> notifyOutFlow ~> notificationSink

        FlowShape(bcast.in, js.out)
      }
    )
  }

  def logFileSource(logId: String, state: State) =
    FileIO.fromPath(logStateFile(logId, state))

  def archiveSink(logId: String) =
    FileIO.toPath(logFile(logId), Set(CREATE, WRITE, APPEND))

  def logFileSink(logId: String) =
    FileIO.toPath(logFile(logId), Set(CREATE, WRITE, APPEND))

  def logStateFile(logId: String, state: State) =
    logFile(s"$logId-${State.norm(state)}")


  import akka.stream.SourceShape
  import akka.stream.scaladsl.{ GraphDSL, Merge }

  def mergeNotOk(logId: String): Source[ByteString, NotUsed] = {
    val warning = logFileSource(logId, Warning).via(LogJson.jsonFramed(maxJsObject))
    val error = logFileSource(logId, Error).via(LogJson.jsonFramed(maxJsObject))
    val critical = logFileSource(logId, Critical).via(LogJson.jsonFramed(maxJsObject))

    Source.fromGraph(
      GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits._

        val warningShape = builder.add(warning)
        val errorShape = builder.add(error)
        val criticalShape = builder.add(critical)

        val merge = builder.add(Merge[ByteString](3))
        warningShape ~> merge
        errorShape ~> merge
        criticalShape ~> merge

        SourceShape(merge.out)
      }
    )
  }

  def logFileSource(logId: String) =
    FileIO.fromPath(logFile(logId))
  def logFileSink(logId: String) =
    FileIO.toPath(logFile(logId), Set(CREATE, WRITE, APPEND))

  def routes: Route =
    getLogsRoute ~ getLogNotOkRoute ~
    postRoute ~
    getLogStateRoute ~ getRoute ~
    deleteRoute

  implicit val unmarshaller = EventUnmarshaller.create(maxLine, maxJsObject)
  implicit val jsonStreamingSupport = EntityStreamingSupport.json()

  def postRoute =
    pathPrefix("logs" / Segment) { logId =>
      pathEndOrSingleSlash {
        post {
          entity(asSourceOf[Event]) { src =>
            onComplete(src.via(processEvents(logId)).toMat(archiveSink(logId))(Keep.right).run()) {
              case Success(IOResult(count, Success(Done))) =>
                complete((StatusCodes.OK, LogReceipt(logId, count)))
              case Success(IOResult(count, Failure(e))) =>
                complete((StatusCodes.BadRequest, ParseError(logId, e.getMessage)))
              case Failure(e) =>
                complete((StatusCodes.BadRequest, ParseError(logId, e.getMessage)))
            }
          }
        }
      }
    }

  implicit val marshaller = LogEntityMarshaller.create(maxJsObject)

  def getRoute =
    pathPrefix("logs" / Segment) { logId =>
      pathEndOrSingleSlash {
        get {
          extractRequest { req => 
            if (Files.exists(logFile(logId))) {
              val src = logFileSource(logId)
              complete(Marshal(src).toResponseFor(req))
            } else {
              complete(StatusCodes.NotFound)
            }
          }
        }
      }
    }

  val StateSegment = Segment.flatMap {
    case State(state) => Some(state)
    case _ => None
  }

  def getLogStateRoute =
    pathPrefix("logs" / Segment / StateSegment) { (logId, state) =>
      pathEndOrSingleSlash {
        get {
          extractRequest { req =>
            if (Files.exists(logStateFile(logId, state))) {
              val src = logFileSource(logId, state)
              complete(Marshal(src).toResponseFor(req))
            } else {
              complete(StatusCodes.NotFound)
            }
          }
        }
      }
    }

  import akka.stream.scaladsl.Merge

  def mergeSources[E](sources: Vector[Source[E, _]]): Option[Source[E, _]] = {
    if (sources.size == 0) None
    else if (sources.size == 1) Some(sources(0))
    else {
      Some(Source.combine(sources(0), sources(1), sources.drop(2) : _*)(Merge(_)))
    }
  }

  def getFileSources[T](dir: Path): Vector[Source[ByteString, Future[IOResult]]] = {
    val dirStream = Files.newDirectoryStream(dir)
    try {
      import scala.collection.JavaConverters._
      paths.map(path =>
        FileIO.fromPath(path)
      ).toVector
    } finally
      dirStream.close
  }

  def getLogsRoute =
    pathPrefix("logs") {
      pathEndOrSingleSlash {
        get {
          extractRequest { req =>
            val sources = getFileSources(logsDir).map { src =>
              src.via(LogJson.jsonFramed(maxJsObject))
            }
          }

          mergeSources(sources) match {
            case Some(src) =>
              complete(Marshal(src).toResponseFor(req))
            case None =>
              complete(StatusCodes.NotFound)
          }
        }
      }
    }

  def getLogsNotOkRoute =
    pathPrefix("logs" / Segment / "not-ok") { logId =>
      pathEndOrSingleSlash {
        get {
          extractRequest { req =>
            complete(Marshal(mergeNotOk(logId)).toResponseFor(req))
          }
        }
      }
    }

  def deleteRoute =
    pathPrefix("logs" / Segment) { logId =>
      pathEndOrSingleSlash {
        delete {
          if (Files.deleteIfExists(logFile(logId))) {
            complete(StatusCodes.OK)
          } else {
            complete(StatusCodes.NotFound)
          }
        }
      }
    }
}
