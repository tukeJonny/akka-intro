package aia.cluster
package words

import java.net.URLEncoder

import akka.actor._
import akka.actor.Terminated

object JobReceptionist {
  def props =
    Props(new JobReceptionist)

  def name =
    "receptionist"

  // Mainから送信される処理対象テキストを含むジョブ要求
  case class JobRequest(name: String, text: List[String])

  // 応答(成功, 失敗)
  // 成功は結果を含む
  sealed trait Response
  case class JobSuccess(name: String, map: Map[String, Int]) extends Response
  case class JobFailure(name: String) extends Response

  // JobMasterによってreduceされた単語カウントの結果
  case class WordCount(name: String, map: Map[String, Int])

  // JobMasterに要求するJobのメッセージ
  case class Job(name: String, text: List[String], respondTo: ActorRef, jobMaster: ActorRef)
}

trait CreateMaster {
  def context: ActorContext
  def createMaster(name: String) =
    context.actorOf(JobMaster.props, name)
}

class JobReceptionist extends Actor
  with ActorLogging
  with CreateMaster {

  import JobReceptionist._
  import JobMaster.StartJob
  import context._

  // JobMasterをスーパバイズする戦略
  override def supervisorStrategy: SupervisorStrategy =
    SupervisorStrategy.stoppingStrategy

  // ジョブ管理(リトライ処理も行う)
  val jobs = Set[Job]()
  val retries = Map[String, Int]()
  val maxRetries = 3

  // MainからJobRequestを受け取り
  // JobMasterからWordCountを受け取り
  // JobMasterから終了メッセージを受け取る
  def receive = {
    // MainからJobRequestを受けて、JobMasterを作成してジョブを流す
    case jr @ JobRequest(name, text) =>
      log.info(s"Received job $name")

      // URLを元に一意なJobMaster名を作成して、JobMasterを作成
      val masterName = "master-"+URLEncoder.encode(name, "UTF8")
      val jobMaster = createMaster(masterName)

      // ジョブを作成し、jobsに登録
      val job = Job(name, text, sender, jobMaster)
      jobs = jobs + job

      // JobMasterにJobを投げて、返事を待つ
      jobMaster ! StartJob(name, text)
      watch(jobMaster)
    // JobMasterから結果を受け取る
    case WordCount(jobName, map) =>
      log.info(s"Job $jobName complete.")
      log.info(s"Result:${map}")

      // 返ってきた結果に対応づくジョブについて、
      // Mainに結果を返して、jobMasterを停止し、
      // 完了したのでjobsから削除という動作をする
      jobs.find(_.name == jobName).foreach { job =>
        job.respondTo ! JobSuccess(jobName, map)
        stop(job.jobMaster)
        jobs = jobs - job
      }
    // jobMasterの終了を検知する(ここでリトライ処理を実装
    case Terminated(jobMaster) =>
      // 失敗したジョブを抽出
      jobs.find(_.jobMaster == jobMaster).foreach { failedJob =>
        log.error(s"Job Master $jobMaster terminated before finishing job.")
        log.error(s"Job ${failedJob.name} failed.")

        val name = failedJob.name
        val nrOfRetries = retries.getOrElse(name, 0)
        // まだリトライできるのであれば
        if (maxRetries > nrOfRetries) {
          // 最大リトライ数ちょうどに達した場合
          if (nrOfRetries == maxRetries - 1) {
            // ジョブのテキストにFAILを含めることで、擬似的に障害を発生させられるように
            // シミュレートする
            val text = failedJob.text.filterNot(_.contains("FAIL"))
            self.tell(JobRequest(name, text), failedJob.respondTo)
          } else // まだ最大リトライ数ではない場合は、そのままJobを自身に再送
            self.tell(JobRequest(name, failedJob.text), failedJob.respondTo)

          // リトライ回数を更新
          // retriesから回数を取得できれば + 1
          // もしまだカウントが始まってなくて、取得できなければ1
          // に更新
          retries = retries + retries.get(name).map(r =>
              name -> (r + 1)
          ).getOrElse(name -> 1)
        }
      }
  }
}
