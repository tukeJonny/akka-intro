package aia.stream

import java.nio.file.{ Path, Paths }
import java.nio.file.StandardOpenOption
import java.nio.file.StandardOpenOption._

import scala.concurrent.Future

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, IOResult }
import akka.util.ByteString

import spray.json._
import com.typesafe.config.{ Config, ConfigFactory }

// EventMarshallingトレイトを継承
object BidiEventFilter extends App with EventMarshalling {
  val config = ConfigFactory.load()
  val maxLine = config.getInt("log-stream-processor.max-line")
  val maxJsonObject = config.getInt("log-stream-processor.max-json-object")

  if (args.length != 5) {
    System.err.println("Provide args: input-format output-format input-file output-file state")
    System.exit(1)
  }

  val inputFile = FileArg.shellExpanded(args(0))
  val outputFile = FileArg.shellExpanded(args(1))
  val filterState = args(4) match {
    case State(state) => state
    case unknown =>
      System.err.println(s"Unknown state $unknown, exiting.")
      System.exit(1)
  }


  // Flow[In, Out, Mat]
  val inFlow: Flow[ByteString, Event, NotUsed] =
    // JSONファイルの場合は、スキャンして、UTF8にしてEventに変換する
    if (args(0).toLowerCase == "json") {
      JsonFraming.objectScanner(maxJsonObject)
      .map(_.decodeString("UTF8").parseJson.convertTo[Event])
    } else {
      // JSONファイルでない場合は、改行で切ってUTF8して解析して
      // Optionから抜き出す
      Framing.delimiter(ByteString("\n"), maxLine)
        .map(_.decodeString("UTF8"))
        .map(LogStreamProcessor.parseLineEx)
        .collect { case Some(event) => event }
    }

    // ここから!!!!!!!!!!!!!!!!!!!!!!
    val outFlow: Flow[Event, ByteString, NotUsed] =
      if (args(1).toLowerCase == "json") {
        // json形式が要求されている場合はjsonをByteStringにしたものを返す
        Flow[Event].map(event => ByteString(event.toJson.compactPrint))
      } else {
        // そうでない場合、eventをログの１行としてシリアライズ
        Flow[Event].map{ event => ByteString(LogStreamProcessor.logLine(event)) }
      }

    // inFlow, outFlowによる双方向(bi-directional)フローを作成
    val bidiFlow = BidiFlow.fromFlows(inFlow, outFlow)

    val source: Source[ByteString, Future[IOResult]] =
      FileIO.fromPath(inputFile)

    val sink: Sink[ByteString, Future[IOResult]] =
      FileIO.toPath(outputFile, Set(CREATE, WRITE, APPEND))

    // 指定のステートに合致するものだけ通すフィルタ
    val filter: Flow[Event, Event, NotUsed] =
      Flow[Event].filter(_.state == filterState)

    // 双方向フィルタとステートフィルターを結合
    val flow = bidiFlow.join(filter)

    // source(fileio) -> flow(bidiFlow + filter) -> sink
    // でマテリアライズし、右の値のみ取る
    val runnableGraph: RunnableGraph[Future[IOResult]] =
      source.via(flow).toMat(sink)(Keep.right)

  /**
   * 開始
   */
  implicit val system = ActorSystem()
  implicit val ec = system.dispatcher
  implicit val materializer = ActorMaterializer()

  runnableGraph.run().foreach { result =>
    println(s"Wrote ${result.count} bytes to $outputFile.")
    system.terminate()
  }
}
