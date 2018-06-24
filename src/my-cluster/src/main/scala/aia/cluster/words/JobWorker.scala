package aia.cluster
package words

import scala.concurrent.duration._

import akka.actor._

object JobWorker {
  def props =
    Props(new JobWorker)

  case class Work(jobName: String, master: ActorRef)
  case class Task(input: List[String], master: ActorRef)

  // ワークロードが劣化
  case object WorkLoadDepleted
}

class JobWorker extends Actor
  with ActorLogging {

  import JobMaster._
  import JobWorker._
  import context._

  // 進行中タスク数
  val processed = 0

  // 単語カウント処理
  def processTask(textPart: List[String]): Map[String, Int] = {
    // 単語に分割
    textPart.flatMap(_.split("\\W+"))
      .foldLeft(Map.empty[String, Int]) { (count, word) => // 左畳み込みですべての単語を
                                                           // Mapに突っこむ
        // FAILが含まれる場合に障害をシミュレート
        if (word == "FAIL")
          throw new RuntimeException("SIMULATED FAILURE!")

        // count: Map[String, Int]にすでに登録された単語なら+1、
        // まだ登録されていない単語なら0+1で初期化する
        count + (word -> (count.getOrElse(word, 0) + 1))
      }
  }

  // デフォルトではアイドル状態
  // 開始後のidle状態でWorkメッセージを受け取り次第すぐにenlist状態に遷移
  // タイムアウトが発生してしまったらretired状態へ遷移
  def receive =
    idle

  /**
   * idle状態の定義
   */
  def idle: Receive = {
    case Work(jobName, master) =>
      become(enlisted(jobName, master))

      log.info(s"Enlisted, woll start requesting work for job '${jobName}'.")
      master ! Enlist(self)
      // 次のタスクを要求
      master ! NextTask
      watch(master)

      setReceiveTimeout(30 seconds)
  }

  /**
   * enlisted状態の定義
   */
  def enlisted(jobName: String, master: ActorRef): Receive = {
    case ReceiveTimeout =>
      master ! NextTask // 再度タスクを要求
    case Task(textPart, master) =>
      val countMap = processTask(textPart)
      processed = processed + 1
      master ! TaskResult(countMap)
      master ! NextTask
    case WorkLoadDepleted =>
      log.info(s"Work load ${jobName} is depleted, retiring ...")
      setReceiveTimeout(Duration.Undefined)
      become(retired(jobName))
    // masterがおっちんだらどうしようもないので自分も死ぬ
    case Terminated(master) =>
      setReceiveTimeout(Duration.Undefined)
      log.error(s"Master terminated that run Job ${jobName}, stopping self.")
      stop(self)
  }

  /**
   * retired状態の定義
   */
  def retired(jobName: String): Receive = {
    // masterがおっちんだらどうしようもないので自分も死ぬ
    case Terminated(master) =>
      log.error(s"Master terminated that run Job ${jobName}, stopping self.")
      stop(self)
    case _ =>
      log.error("I'm retired.")
  }
}
