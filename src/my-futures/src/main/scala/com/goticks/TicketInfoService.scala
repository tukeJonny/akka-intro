package com.goticks

import scala.concurrent.Future
import com.github.nscala_time.time.Imports._
import scala.util.control.NonFatal
import scala.collection.immutable._

// TicketInfoServiceトレイと
trait TicketInfoService extends WebServiceCalls {
  // トラフィック情報を取得
  def getTraffic(ticketInfo: TicketInfo): Future[TicketInfo]=
    ticketInfo.event.map {event =>
      callTrafficService(ticketInfo.userLocation,
        event.location, event.time).map { routeResponse =>
          ticketInfo.copy(travelAdvice = Some(TravelAdvice(routeByCar = routeResponse)))
        }
    }.getOrElse(Future.successful(ticketInfo))

  // 天気情報を取得 (もっとも早く取得できたものを採用)
  def getWeather(ticketInfo: TicketInfo): Future[TicketInfo]={
    val futureWeatherX = callWeatherXService(ticketInfo).recover(withNone)
    val futureWeatherY = callWeatherYService(ticketInfo).recover(withNone)

    Future.firstCompletedOf(Seq(featureWeatherX, featureWeatherY)).map { weatherResponse =>
      // weatherだけ書き換えてコピーしたticketInfoを返す
      ticketInfo.copy(weather = weatherResponse)
    }
  }

  def getTravelAdvice(info: TicketInfo, event: Event): Future[TicketInfo] = {
    val futureRoute = callTrafficService(info.userLocation, event.location, event.time).recover(withNone)
    val futurePublicTransport = callPublicTransportService(info.userLocation, event.location, event.time)

    futureRoute.zip(futurePublicTransport).map { 
      case(routeByCar, publicTransportAdvice) =>
        val travelAdvice = TravelAdvice(routeByCar, publicTransportAdvice)
        info.copy(travelAdvice=Some(travelAdvice))
    }
  }

  /**
   * forで書くとすごい見やすい
   * コンビネータによって適していればforを使うことを検討するのがいいかも
   */
  def getTravelAdviceWithFor(info: TicketInfo, event: Event): Future[TicketInfo] = {
    val futureRoute = callTrafficService(info.userLocation, event.location, event.time).recover(withNone)
    val futurePublicTransport = callPublicTransportService(info.userLocation, event.location, event.time)

    for(
         (route, advice) <- futureRoute.zip(futurePublicTransport);
         travelAdvice = TravelAdvice(route, advice)
    ) yield info.copy(travelAdvice = Some(travelAdvice))
  }

  // called by getSuggestions
  // sequenceを使うよりもtraverseの方がダイレクトに値を返せて綺麗
  // SeqでFutureを包んでるときに、Seqを包むFutureを返そうと思ったら、traverse使えないか検討しよう
  // 多分そういう状況ならtraverseだいたい使えるきがするけども
  def getPlannedEventsWithTraverse(event: Event, artists: Seq[Artist]): Future[Seq[Event]] = {
    Future.traverse(artists) { artist =>
      callArtistCalendarService(artist, event.location)
    }
  }

  // called by getSuggestions
  def getPlannedEvents(event: Event, artists: Seq[Artist]): Future[Seq[Event]] = {
    val events = artists.map(artist =>
        callArtistCalendarService(artist, event.location))

    // Seq[Future[Event]] -> Future[Seq[Event]]
    Future.sequence(events)
  }

  def getSuggestions(event: Event): Future[Seq[Event]] = {
    val futureArtists = callSimilarArticstsService(event).recover(withEmptySeq)

    for(
         artists <- futureArtists.recover(withEmptySeq);
         events  <- getPlannedEvents(event, artists).recover(withEmptySeq)
    ) yield events
  }

  /**
   * recoverに使える独自定義のwithX関数
   * 前のステップのticketInfoで回復
   */
  def withPrevious(previous: TicketInfo): Recovery[TicketInfo] = {

  }

  // チケット情報を取得
  def getTicketInfo(ticketNr: String, location: Location): Future[TicketInfo] = {
    val emptyTicketInfo = TicketInfo(ticketNr, location) // previous
    val eventInfo = getEvent(ticketNr, location).recover(withPrevious(emptyTicketInfo))

    eventInfo.flagMap { info =>
      val infoWithWeather = getWeather(info)
      val infoWithTravelAdvice = info.event.map { event =>
        getTravelAdvice(info, event)
      }.getOrElse(eventInfo)
      val suggestedEvents = info.event.map { event =>
        getSuggestion(event)
      }.getOrElse(Future.successful(Seq()))

      val ticketInfos = Seq(infoWithTravelAdvice, elem.weather)
      
      val infoWithTravelAndWeather: Future[TicketInfo] =
        Future.foldLeft(ticketInfos)(info) { (acc, elem) =>
          val (travelAdvice, weather) = (elem.travelAdvice, elem.weather)
          acc.copy( travelAdvice = travelAdvice.orElse(acc.travelAdvice),
            weather = weather.orElse(acc.weather) )
        }

      for (
           info <- infoWithTravelAndWeather;
           suggestions <- suggestedEvents
      ) yield info.copy(suggestions = suggestions)
    }
  }

  /**
   * リカバリ関連
   */

  // TicketInfoサービスで使ったエラーからの回復メソッド
  type Recovery[T] = PartialFunction[Throwable, T]

  def withNone[T]: Recovery[Option[T]] = {
    case e => None
  }

  def withEmptySeq[T]: Recovery[Seq[T]] = {
    case e => Seq()
  }

  def withPrevious(previous: TicketInfo): Recovery[TicketInfo] = {
    case e => previous
  }
}





trait WebServiceCalls {
  // チケットイベントを取得する
  def getEvent(ticketNr: String, location: Location): Future[TicketInfo]

  
}
