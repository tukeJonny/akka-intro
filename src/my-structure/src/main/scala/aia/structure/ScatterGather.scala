package aia.structure

import java.util.Date
import scala.concurrent.duration._
import scala.collection.mutable.ListBuffer

import akka.actor._
import java.text.SimpleDateFormat

// 写真のメッセージ
case class PhotoMessage(
  id: String, // アグリゲータがメッセージをそれぞれのフローに関連づける際に使う
  photo: String,
  creationTime: Option[Date] = None, // GetTimeタスクによって取得される
  speed: Option[Int] = None // GetSpeedタスクによって取得される
)

// Photo(画像)に関する処理をまとめたコンパニオンオブジェクト
object ImageProcessing {
  val dateFormat = new SimpleDateFormat("ddMMyyyy HH:mm:ss.SSS")

  // スピードを取得
  def getSpeed(image: String): Option[Int] = {
    val attributes = image.split('|')
    if (attributes.size == 3)
      Some(attributes(1).toInt)
    else
      None
  }

  // 時刻を取得
  def getTime(image: String): Option[Date] = {
    val attributes = image.split('|')
    if (attributes.size == 3)
      Some(dateFormat.parse(attributes(0)))
    else
      None
  }

  // ナンバープレートを取得
  def getLicense(image: String): Option[String] = {
    val attributes = image.split('|')
    if (attributes.size == 3)
      Some(attributes(2))
    else
      None
  }

  // 写真の属性を文字列にシリアライズ
  def createPhotoString(date: Date, speed: Int): String = {
    createPhotoString(date, speed, " ")
  }

  // 写真の属性を文字列にシリアライズ(ナンバープレートあり)
  def createPhotoString(date: Date, speed: Int, license: String): String = {
    "%s|%s|%s".format(dateFormat.format(date), speed, license)
  }
}

// スピードを取得する処理タスク
// 詳細な処理タスクはImageProcessing.getSpeedmに委譲している
class GetSpeed(pipe: ActorRef) extends Actor {
  def receive = {
    // 写真のメッセージが来たら
    // getSpeed処理タスクの結果をspeedフィールドに入れたメッセージを返す
    case msg: PhotoMessage => {
      pipe ! msg.copy(speed = ImageProcessing.getSpeed(msg.photo))
    }
  }
}

// 時刻を取得する処理タスク
// 詳細な処理タスクはImageProcessing.getTimeに委譲している
class GetTime(pipe: ActorRef) extends Actor {
  // 写真のメッセージが来たら
  // getTime処理タスクの結果をcreationTimeに入れたメッセージを返す
  def receive = {
    case msg: PhotoMessage => {
      pipe ! msg.copy(creationTime = ImageProcessing.getTime(msg.photo))
    }
  }
}

// 受信者リスト
// 受信した全ての処理対象となるタスクを分散
// GetSpeedやGetTime処理タスクに分散(Scatter)する
// スキャッターする
class RecipientList(recipientList: Seq[ActorRef]) extends Actor {
  def receive = {
    case msg: AnyRef =>
      recipientList.foreach(_ ! msg) // Send
  }
}

// タイムアウトを知らせるメッセージ
case class TimeoutMessage(msg: PhotoMessage)

// アグリゲーターパターん
// ここでギャザーする
class Aggregator(timeout: FiniteDuration, pipe: ActorRef) extends Actor {
  // 保留メッセージを格納するバッファ(なるべくメッセージ数を少なくするために、バッファに保持しておいて
  // ある程度溜まったらまとめてみたいなことをする）
  val messages = new ListBuffer[PhotoMessage]

  implicit val ec = context.system.dispatcher

  // リスタート前に、バッファに溜まっているメッセージを自身に送出
  // 万一障害でおっちんでも、自身に送出されたメッセージは、自分の代わりに作られた
  // アクターにちゃんと送られてくる
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    messages.foreach(self ! _)
    messages.clear()
  }

  def receive = {
    case rcvMsg: PhotoMessage => {
      // バッファからIDが一致するPhotoMessageを探し出す
      messages.find(_.id == rcvMsg.id) match {
        // 見つかった(同じメッセージが過去に保留されていた)
        case Some(alreadyRcvMsg) => {
          // 受信したメッセージでPhotoMessageを作成する.
          // この時、creationTimeとspeedは処理タスクから取得されて来て、Option
          // の文脈になっているのでorElseでデフォルトを元の値にして取得する
          // つまり、保留メッセージと今処理できるメッセージを結合して良いのでそうするということである
          val newCombinedMsg = new PhotoMessage(rcvMsg.id, rcvMsg.photo,
            rcvMsg.creationTime.orElse(alreadyRcvMsg.creationTime),
            rcvMsg.speed.orElse(alreadyRcvMsg.speed))
          pipe ! newCombinedMsg // 作ったPhotoMessageを後続処理にパイプで流す
          messages -= alreadyRcvMsg // 処理済みのalreadyRcvMsgをバッファから削除(１つ前のメッセージがわかればいい)
        }

        // 見つからなかった(特に保留されてなさそう)
        // 受診したメッセージをバッファにぶち込み、(保留にする.このまま送るとメッセージ数多くなっちゃうので、ある程度まとめたい）
        // バッファにぶち込んでるので、同じIDのPhotoMessageが来たらCombineされてpipeに流されるはず
        // そうでなく、タイムアウトしたら一度だけTimeoutを知らせるTimeoutMessageを送出するように
        // スケジュールする
        case None => {
          messages += rcvMsg
          context.system.scheduler.scheduleOnce(timeout, self, new TimeoutMessage(rcvMsg))
        }
      }
    }

    // TimeoutMessage(バッファに同じIDのPhotoMessageが見つからな買ったメッセージ)
    // を受診
    case TimeoutMessage(rcvMsg) => {
      messages.find(_.id == rcvMsg.id) match {
        // メッセージは処理されてなかった
        // まとめるのを諦めて単発で送る
        // で、バッファから消す
        case Some(alreadyRcvMsg) => {
          pipe ! alreadyRcvMsg
          messages -= alreadyRcvMsg
        }
        case None => // メッセージは処理済み(OK)
      }
    }

    case ex: Exception =>
      throw ex // 例外を送出
  }
}
