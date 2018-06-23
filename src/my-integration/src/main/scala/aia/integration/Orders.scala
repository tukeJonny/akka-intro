package aia.integration

import java.nio.file.Path

import akka.NotUsed
import akka.stream.alpakka.amqp.{ AmqpSinkSettings, AmqpSourceSettings }
import akka.stream.alpakka.amqp.scaladsl.{ AmqpSink, AmqpSource }
import akka.stream.alpakka.file.DirectoryChange
import akka.stream.alpakka.scaladsl.DirectoryChangesSource
import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.util.ByteString

import scala.xml.XML
import scala.concurrent.duration._

object Orders {

  case class Order(customerId: String, productId: String, number: Int)

  // XML String -> Order message
  val parseOrderXmlFlow = Flow[String].map { xmlString =>
    val xml = XML.loadString(xmlString)
    val order = xml \\ "order"
    val customer = (order \\ "customerId").text
    val productId = (order \\ "productId").text
    val number = (order \\ "number").text.toInt

    new Order(customer, productId, number)
  }

  // Source
  object FileXmlOrderSource {
    def watch(dirPath: Path): Source[Order, NotUsed] =
      DirectoryChangesSource(dirPath, pollInterval = 500.millis, maxBufferSize = 1000)
        .collect { case (path, DirectoryChange.Creation) => path } // ファイルの作成を検知し
        .map(_.toFile) // ファイルオブジェクトに変換し
        .filter(file => file.isFile && file.canRead) // ファイルかつ読み出し可能なファイルに絞って
        .map(scala.io.Source.fromFile(_).mkString) // 文字列を得て
        .via(parseOrderXmlFlow) // parseOrderXmlFlowに流す(Orderメッセージに変換する)
  }

  object AmqpXmlOrderSource {
    def apply(amqpSourceSettings: AmqpSourceSettings): Source[Order, NotUsed] =
      // AmqpSourceは以下の２つのファクトリメソッドを持つ
      // * atMostOnceSource ... メッセージを最大で一度受け取れることが保障される
      // * committableSource ... 少なくとも一度受け取れるが、メッセージが重複する可能性がある
      AmqpSource.atMostOnceSource(amqpSourceSettings, bufferSize = 10) // 
        .map(_.bytes.utf8String) // UTF-8 Encoded Stringに変換
        .via(parseOrderXmlFlow) // Orderメッセージに変換する
  }

  // Sink
  object AmqpXmlOrderSink {
    def apply(amqpSinkSettings: AmqpSinkSettings): Sink[Order, NotUsed] =
      Flow[Order]
        .map { order =>
          <order>
            <customerId>{ order.customerId }</customerId>
            <productId>{ order.productId }</productId>
            <number>{ order.number }</number>
          </order>
        }
          .map(xml => ByteString(xml.toString))
          .to(AmqpSink.simple(amqpSinkSettings))
  }
}
