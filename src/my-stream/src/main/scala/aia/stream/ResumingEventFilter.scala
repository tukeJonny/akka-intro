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
object EventFilter extends App with EventMarshalling {
  val config = ConfigFactory.load()
  val maxLine = config.getInt("log-stream-processor.max-line")

  if (args.length != 3) {
    System.err.println("Provide args: input-file output-file state")
    System.exit(1)
  }

  val inputFile = FileArg.shellExpanded(args(0))
  val outputFile = FileArg.shellExpanded(args(1))

  val filterState = args(2) match {
    case State(state) => state
    case unknown =>
      System.err.println(s"Unknown state $unknown, exiting.")
      System.exit(1)
  }

  import akka.stream.scaladsl._

  val source: Source[ByteString, Future[IOResult]] =
    FileIO.fromPath(inputFile)

  val sink: Sink[ByteString, Future[IOResult]] =
    FileIO.toPath(outputFile, Set(CREATE, WRITE, APPEND))

  /**
   * maxLineまで\nで区切って、UTF8に変換して、行を解析し、
   * Optionを取り出し、フィルタする状態でフィルタし、
   * ByteStringにする
   */
  val flow: Flow[ByteString, ByteString, NotUsed] =
    Framing.delimiter(ByteString("\n"), maxLine)
      .map(_.decodeString("UTF8"))
      .map(LogStreamProcessor.parseLineEx) // ExはExceptionの意. 解析できなければ例外を投げる
      .collect { case Some(e) => e }
      .filter(_.state == filterState)
      .map(event => ByteString(event.toJson.compactPrint))

  /**
   * maxLineまで\nで区切って、UTF8に変換
   */
  val frame: Flow[ByteString, String, NotUsed] =
    Framing.delimiter(ByteString("\n"), maxLine)
      .map(_.decodeString("UTF8"))


  import akka.stream.ActorAttributes
  import akka.stream.Supervision

  import LogStreamProcessor.LogParseException

  // アクター監督のDeciderを定義
  val decider: Supervision.Decider = {
    case _: LogParseException => Supervision.Resume
    case _                    => Supervision.Stop
  }

  /**
   * 行を解析し、Optionを取り出す
   * Deciderを渡して、例外に応じてResumeやStopを行うようにする
   */
  val parse: Flow[String, Event, NotUsed] =
    Flow[String].map(LogStreamProcessor.parseLineEX)
      .collect { case Some(e) => e }
      .withAttributes(ActorAttributes.supervisionStrategy(decider))

  /**
   * 指定された状態だけにする
   */
  val filter: Flow[Event, Event, NotUsed] =
    Flow[Event].filter(_.state == filterState)

  // EventをByteStringに変換する
  val serialize: Flow[Event, ByteString, NotUsed] =
    Flow[Event].map(event => ByteString(event.toJson.compactPrint))

  /**
   * 開始
   */
  implicit val system = ActorSystem()
  implicit val ec = system.dispatcher


  // RunnableGraph用のDeciderを定義
  val graphDecider: Supervision.Decider = {
    case _: LogParseException => Supervision.Resume
    case _                    => Supervision.Stop
  }

  // Materializerに監督戦略を設定する
  import akka.stream.ActorMaterializerSettings
  implicit val materializer = ActorMaterializer(
    ActorMaterializerSettings(system)
      .withSupervisionStrategy(graphDecider)
  )

  // 解析して、フィルタして、しリアライズする
  val composedFlow: Flow[ByteString, ByteString, NotUsed] =
    frame.via(parse)
      .via(filter)
      .via(serialize)

  val runnableGraph: RunnableGraph[Future[IOResult]] =
    source.via(composedFlow).toMat(sink)(Keep.right)

  runnableGraph.run().foreach { result =>
    println(s"Wrote ${result.count} bytes to $outputFile.")
    system.terminate()
  }
}
