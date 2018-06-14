package aia.stream

import java.nio.file.Path

import java.time.ZonedDateTime
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import akka.NotUsed
import akka.util.ByteString
import akka.stream.IOResult
import akka.stream.scaladsl.{ FileIO, Framing, RUnnableGrpah, Source, Flow,SubFlow }
import spray.json._

object LogStreamProcessor extends EventMarshalling {
  def logLines(path: Path): Source[String, Future[IOResult]] =
    delimitedText(FileIO.fromPath(path), 1024 * 1024)

  def convertToString[T](source: Source[ByteString, T]): Source[String, T] =
    source.map(_.decodeString("UTF8"))

  def delimitedText[T](source: Source[ByteString, T], maxLine: Int): Source[String, T] =
    convertToString(source.via(Framing.delimiter(ByteString("\n"), maxLine)))

  def parseLogEvents[T](source: Source[String, T]): Source[Event, T] =
    source.map(parseLineEx)
      .collect{ case Some(e) => e }

  def errors[T](source: Source[Event, T]): Source[Event, T] =
    source.filter(_.state == Error)

  // groupedWithinはnrEvent個にグループ分けするようだ
  def rollup[T](source: Source[Event, T], predicate: Event => Boolean, 
                nrEvents: Int, duration: FiniteDuration): Source[Seq[Event], T] =
    source.filter(predicate).groupedWithin(nrEvents, duration)

    def groupByHost[T](source: Source[Event, T]) = {
      source.groupBy(10, e => (e.host, e.service))
    }

    def convertToJsonBytes[T](flow: Flow[Seq[Event], Seq[Event], T]): Flow[Seq[Event], ByteString, T] =
      flow.map(events => ByteString(events.toJson.compactPrint))

    def convertToJsonBytes[T](source: Source[Event, T]): Source[ByteString, T] =
      source.map(event => ByteString(event.toJson.compactPrint))

    def jsonText(path: Path): Source[String, Future[IOResult]] =
      jsonText(FileIO.fromPath(path), 1024 * 1024)

    def jsonText[T](source: Source[ByteString, T], maxObject: Int): Source[String, T] =
      convertToString(source.via(akka.stream.scaladsl.JsonFraming.objectScanner(maxObject)))

    def parseJsonEvents[T](source: Source[String, T]): Source[Event, T] =
      source.map(_.parseJson.convertTo[Event])

    def parseLineEx(line: String): Option[Event] = {
      if (!line.isEmpty) {
        line.split("\\|") match {
          // タグ、メトリックもある場合
          case Array(host, service, state, time, desc, tag, metric) =>
            val t = tag.trim
            val m = metric.trim

            Some(Event(host.trim, service.trim, state.trim match {
              case State(s) => s
              case _ => throw new Exception(s"Unexpected state: $line")
            }, ZonedDateTime.parse(time.trim), desc.trim,
            if (t.nonEmpty) Some(t) else None,
            if (m.nonEmpty) Some(m.toDouble) else None
            ))
          case Array(host, service, state, time, desc) =>
            Some(Event(host.trim, service.trim, state.trim match {
              case State(s) => s
              case _ => throw new Exception(s"Unexpected state: $line")
            }, ZonedDateTime.parse(time.trim), desc.trim))
          case x =>
            throw new LogParseException(s"Failed on line: $line")
        }
      } else None
    }

  def logLine(event: Event) = {
    s"""${event.host} | ${event.service} | ${State.norm(event.state)} | ${event.time.toString} | ${event.description} ${if(event.tag.nonEmpty) "|" + event.tag.get else "|" } ${if(event.metric.nonEmpty) "|" + event.metric.get else "|" }\n"""
  }

  case class LogParseException(msg: String) extends Exception(msg)
}
