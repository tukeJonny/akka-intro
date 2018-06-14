package aia.stream

import java.nio.file.{ Path, Paths }
import java.nio.file.StandardOpenOption
import java.nio.file.StandardOpenOption._
import scala.concurrent.Future

import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, IOResult }
import akka.stream.scaladsl.{ FileIO, RunnableGraph, Source, Sink }
import akka.util.ByteString

import com.typesafe.config.{ Config, ConfigFactory }

object StreamingCopy extends App {
  val config = ConfigFactory.load()
  val maxLine = config.getInt("log-stream-processor.max-line")

  if (args.length != 2) {
    System.err.println("Provide args: input-file output-file")
    System.exit(1)
  }

  val inputFile = FileArg.shellExpanded(args(0))
  val outputFile = FileARg.shellExpanded(args(1))

  /**
   * ソースとシンクはRunnableGraph実行時に補助値(=Future[IOResult]とか）
   * を提供できる
   *
   * これをマテリアライズされた値と呼ぶ
   *
   * 入力バッファのチャンクサイズはデフォで8KBらしい
   * 入力バッファーサイズの最大値は要素の最大数を指定するパラメータで、
   * akka.stream.materializer.max-input-buffer-size項目で設定できる
   * デフォでは16なので 16 * 8KB(=チャンクサイズ) = 128KB
   */

  // ファイルからデータを読み出すソース
  // FilePublisherを作成し、FileChannelが作成される
  // FilePublisherは、FileSubscriberから受け取ったRequest
  // メッセージをもとに、それより小さいbytesを
  // OnNext(bytes)メッセージでサブスクライバに送信する
  val source: Source[ByteString, Future[IOResult]] =
    FileIO.fromPath(inputFile)

  // ファイルにデータを書き出すシンク
  // FileSubscriberを作成し、FileChannelが作成される
  // FileSubscriberはRequest(size)メッセージを送信し、
  // size分までしか受け取れないよとPublisherに伝える
  // このように許容サイズをパブリッシャーに知らせることを
  // ノンブロッキングback pressureと呼ぶ
  val sink: Sink[ByteString, Future[IOResult]] =
    FileIO.toPath(outputFile, Set(CREATE, WRITE, APPEND))

  // ソースとシンクをつなげる
  // SourceとSinkが接続されたRunnableGraphが作成される
  // マテリアライザは、このグラフを設計図に、Pub-Subを作って
  // SourceからSinkに流せるようにしてくれる
  def runnableGraph: RunnableGraph[Future[IOResult]] =
    source.to(sink)

  /**
   * Runnable Graphを実行するための定義
   */

  implicit val system = ActorSystem()
  implicit val ec = system.dispatcher
  // ActorMaterializerは入力と出力が全て
  // 正常に接続されているか確認し、Source及びSinkに
  // パブリッシャーとサブスクライバーを作成するように命令する
  // で、サブスクライバーにパブリッシャをサブスクライブさせて、
  // グラフにストリームを流す
  implicit val materializer = ActorMaterializer()

  // 実行
  runnableGraph.run().foreach { result =>
    println(s"${result.status}, ${result.count} bytes.read.")
    system.terminate()
  }

  /**
   * グラフを通じてマテリアライズされた値
   * マテリアライザーはこれを作るやつって考え方かな
   */
  /**
   * toMatに関数を渡し、
   * Keep(left = source, right = sink) の値をどう取り出すかを決めることができる
   * left, right, bothのメソッドが用意されており、これらを使う場合と即時関数をカスタムで渡すこともできる
   * 今回の場合は、leftに読み込みのIOResult、rightに書き込みのIOResultが入る
   */

  import akka.Done
  import akka.stream.scaldsl.Keep

  val graphLeft: RunnableGraph[Future[IOResult]] =
    source.toMat(sink)(Keep.left)

  val graphRight: RunnableGraph[Future[IOResult]] =
    source.toMat(sink)(Keep.right)

  val graphBoth: RunnableGraph[(Future[IOResult], Future[IOResult])] =
    source.toMat(sink)(Keep.both)

  val graphCustom: RunnableGraph[Future[Done]] =
    source.toMat(sink) { (l, r) =>
      Future.sequence(List(l, r)).map(_ => Done)
    }
}
