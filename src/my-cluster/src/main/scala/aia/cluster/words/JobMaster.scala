package aia.cluster
package words

import scala.concurrent.duration._

import akka.actor._
import akka.cluster.routing._
import akka.routing._

object JobMaster {

}

class JobMaster extends Actor
  with ActorLogging
  with CreateWorkerRouter {

  import JobReceptionist.WordCount
  import JobMaster._
  import JobWorker._
  import context._

  // テキストを構成する部分のリスト
  var textParts = Vector[List[String]]()
  // 中間結果
  var intermediateResult = Vector[Map[String, Int]]()
  // ワーカーのActorRefセット
  var workers = Set[ActorRef]()
  // JobMasterの状態管理(いくつ仕事があてがわれているか、受け取った仕事はいくつか)
  var workGiven = 0
  var workReceived = 0

  // JobWorkerをスーパーバイズ
  override def supervisorStrategy: SupervisorStrategy =
    SupervisorStrategy.stoppingStrategy

  // JobWorkerから帰ってきた単語カウント結果をreduceする
  def merge(): Map[String, Int] = {
    // 中間結果を左畳み込み
    intermediateResult.foldLeft(Map[String, Int]()) { (el, acc) =>
      el.map {
        case (word, count) =>
          // アキュムレータのカウントがすでにあれば、countを足し入れて
          // そうでない場合はcountで初期化
          acc.get(word).map(accCount => 
            (word -> (accCount + count))
          ).getOrElse(word -> count)
      } ++ (acc -- el.keys) // el.mapの結果できるのはelに存在する分だけなので、
                            // elに存在する分を除いたアキュムレータの残りと合わせる
    }
  }

  // 初期はアイドル状態
  // ジョブが開始されるとworkingへ遷移し、
  // 結果が得られればfinishingへ遷移.
  def receive =
    idle

  /**
   * idle状態の定義
   */
  def idle: Receive = {
    case StartJob(jobName, text) =>
      // 10個ずつのテキスト部分でグループ化 -> ベクタライズ
      textParts = text.grouped(10).toVector
      // ルーターへのメッセージ送信をスケジュール
      // すぐに開始し、1000ミリ秒ごとに送りつける
      val cancellable = context.system.scheduler.schedule(
        0 millis,
        1000 millis,
        router, // ルーター
        Work(jobName, self)
      )
      // 受信タイムアウトを60秒に設定
      context.setReceiveTimeout(60 seconds)

      // working状態へ遷移
      become(working(jobName, sender, cancellable))
  }

  /**
   * working状態の定義
   */
  def working(jobName: String, receptionist: ActorRef, cancellable: Cancellable): Receive = {
    // Jobworkerから参加メッセージが来た場合
    case Enlist(worker) =>
      // workerを監視して、workersに突っ込む
      watch(worker)
      workers = workers + worker
    // Jobworkerから次のタスクくれ要求が来た場合
    case NextTask =>
      if (textParts.isEmpty) {
        // タスクがない
        sender() ! WorkLoadDepleted
      } else {
        sender() ! Task(textParts.head, self)
        workGiven = workGiven + 1 // ワーカに１つ仕事あげた
        textParts = textParts.tail // 更新
      }
    // Jobworkerからタスク処理結果が帰ってきた場合
    case TaskResult(countMap) =>
      // 中間結果に結果をぶち込む
      intermediateResult = intermediateResult :+ countMap
      workReceived = workReceived + 1 // 処理結果１つ受け取ったよ

      // タスクがすべて完了したら
      if (textParts.isEmpty && workGiven == workReceived) {
        cancellable.cancel() // ルーターの転送処理を停止
        // 終了状態へ移行
        become(finishing(jobName, receptionist, workers))
        // 受信タイムアウトを無効化
        setReceiveTimeout(Duration.Undefined)
        self ! MergeResults // Reduce処理実行を自分に要求
      }
    // 受信タイムアウトになった場合
    case ReceiveTimeout =>
      // 誰も応答出来ないようであればキャンセル処理
      // 誰か応答しているなら、ジョブを完遂するために受信タイムアウトを無効化
      if (workers.isEmpty) {
        log.info(s"No workers responded in time. Cancelling job $jobName.")
        stop(self)
      } else
        setReceiveTimeout(Duration.Undefined)
    // Jobworkerが死んだ
    case Terminated(worker) =>
      // タスク遂行は無理だと判断し、キャンセル処理
      log.info(s"Worker $worker got terminated. Cancelling job $jobName.")
      stop(self)
  }

  /**
   * 終了処理
   */
  def finishing(jobName: String, receptionist: ActorRef, workers: Set[ActorRef]): Receive = {
    // 結果のreduce処理
    case MergeResults =>
      val mergedMap = merge()
      workers.foreach(stop(_)) // workerをすべて殺す
      receptionist ! WordCount(jobName, mergedMap) // 受信者アクターに結果をあげる
    // jobWorkerが死んだ
    case Terminated(worker) =>
      log.info(s"Job $jobName is finishing. Worker ${worker.path.name} is stopped.")
  }
}

trait CreateWorkerRouter { this: Actor =>
  def createWorkerRouter: ActorRef = {
    // ルータープールのクラスタリング設定
    val settings = ClusterRouterPoolSettings(
      totalInstances = 100, // クラスターのワーカー最大数
      maxInstancesPerNode = 20, // １ノードあたりワーカー最大数
      allowLocalRoutees = false, // ローカルにルーティを作成しない
      useRole = None // ここにroleを指定すると、指定されたroleにだけルーティングされる
    )

    // 設定によりクラスタリングされたBroadCastPoolルータ作成
    context.actorOf(ClusterRouterPool(
      BroadcastPool(10),
      settings
    ).props(Props[JobWorker]), name = "worker-router")
  }
}
