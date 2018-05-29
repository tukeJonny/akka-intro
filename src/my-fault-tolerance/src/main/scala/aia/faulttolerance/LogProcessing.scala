package aia.faulttolerance

import java.io.File
import java.util.UUID
import akka.actor._
import akka.actor.SupervisorStorategy.{Stop, Resume, Restart}
import akka.actor.OneForOneStorategy

package dbstrategy {

  /**
   * LogProcessingアクターシステム
   */
  object LogProcessingApp extends App {
    val sources = Vector("file:///source1/", "file:///source2/")
    val system = ActorSystem("logprocessing")

    val databaseUrl = Vector(
      "http://mydatabase1",
      "http://mydatabase2",
      "http://mydatabase3"
    )

    // LogProcessingSupervisor 最上位アクターを生成
    system.actorOf(
      LogProcessingSupervisor.props(sources, databaseUrl),
      LogProcessingSupervisor.name
    )
  }

  /**
   * LogProcessingSupervisorアクターのコンパニオンオブジェクト
   */
  object LogProcessingSupervisor {
    def props(sources: Vector[String], databaseUrls: Vector[String]) =
      Props(new LogProcessingSupervisor(sources, databaseUrls))

    def name = "file-watcher-supervisor"
  }

  /**
   * LogProcessingSupervisorアクター
   */
  class LogProcessingSupervisor(sources: Vector[String], databaseUrls: Vector[String]) extends Actor
                                                                                        with ActorLogging {
    /**
     * ソースファイルそれぞれについてFileWatcherアクターを生成し、監視し、返す
     */
    var fileWatchers: Vector[ActorRef] = sources.map { source =>
      var fileWatcher = context.actorOf(
        Props(new FileWatcher(source, databaseUrls))
      )
      context.watch(fileWatcher)
      fileWatcher
    }

    /**
     * ディスクのエラーが起きたらもう続けようがないので、みんな道連れで停止
     * みんなは一人のために
     */
    override def supervisorStrategy = AllForOneStrategy() {
      case _: DiskError => Stop
    }

    /**
     * FileWatcherがおっ死んだらそいつ以外のFileWatcherを探して、無ければシステムを終える
     */
    def receive = {
      case Terminated(fileWatcher) =>
        fileWatchers = fileWatchers.filterNot(_ == fileWatcher)
        if (fileWatchers.isEmpty) {
          log.info("Shutting down, all file watchers have failed.")
          context.system.terminate()
        }
    }
  }

  /**
   * LogProcessorアクターのコンパニオンオブジェクト
   */
  object LogProcessor {
    // Propsで包んだ新しいアクターを生成して返す
    def props(databaseUrls: Vector[String]) = 
      Props(new LogProcessor(databaseUrls))

    def name = s"log_processor_${UUID.randomUUID.toString}"

    // File監視アクターからファイル名を受信する際のメッセージ
    case class LogFile(file: File)
  }

  /**
   * DbWriterアクターのコンパニオンオブジェクト
   */
  object DbWriter {
    def props(databaseUrl: String) = 
      Props(new DbWriter(databaseUrl))

    def name(databaseUrl: String) =
      s"""db-writer-${databaseUrl.split("/").last}"""

    // LogProcessorアクターから受け取るログファイルの行
    case class Line(time: Long, message: String, messageType: String)
  }

  /**
   * DbWriterアクター
   */
  class DbWriter(databaseUrl: String) extends Actor {
    val connection = new DbCon(databaseUrl)

    import DbWriter._
    // LogProcessorからログの１行を受け取ったら、Map化してDBに書き込む
    def receive = {
      case Line(time, message, messageType) =>
        connection.write(Map('time -> time,
          'message -> message,
          'messageType -> messageType))
    }
    
    // DBWriteアクターがクラッシュ or 停止時、DBとのコネクションを切断する
    override def postStop(): Unit = {
      connection.close()
    }
  }

  /**
   * LogProcessorアクター
   */
  class LogProcessor(databaseUrls: Vector[String]) extends Actor with ActorLogging with LogParsing {
    // databaseノードがわからないのはダメ
    require(databaseUrls.nonEmpty)

    // 最初はURL一覧の頭を使う。
    // 代わりとして、ケツのURLを使う
    val initialDatabaseUrl = databaseUrls.head
    val alternateDatabases = databaseUrls.tail

    // 子アクターは同じ運命を共有しない(１つアクターおっちんでも他のアクターは残る)
    // つまり、一人は一人のためにって感じ
    // で、具体的にどうおっ死ぬかというと、コネクションであればもっかい試して
    // DB落ちてるなら停止するという感じ
    override def supervisorStrategy = OneForOneStrategy() {
      case _: DbBrokenConnectionException => Restart
      case _: DbNodeDownException => Stop
    }

    // DBWriterアクターを生成
    var dbWriter = context.actorOf(
      DBWriter.props(initialDatabaseUrl),
      DBWriter.name(initialDatabaseUrl)
    )
    // DBWriterの状態変化通知を受け取るように監視
    context.watch(dbWriter)

    import LogProcessor._
    def receive = {
      // ログファイルが指定されたメッセージが来たら、
      // 行をパーサーによって取り出し、各行についてdbWriterにメッセージを送る
      case LogFile(file) =>
        var lines: Vector[DbWriter.Line] = parse(file)
        lines.foreach(dbWriter ! _)
      // context.watchしているdbWriterがTerminatedになったら
      case Terminated(_) =>
        // もし代わりになるDBノードがあるなら、そのノードで新たなDBWriterアクターを生成する
        if (alternateDatabases.nonEmpty) {
          val newDatabaseUrl = alternateDatabases.head
          alternateDatabases = alternateDatabases.tail

          dbWriter = context.actorOf(
            DbWriter.props(newDatabaseUrl),
            DBWriter.name(newDatabaseUrl)
          )
          context.watch(dbWriter)
        } else {
          // もし代わりになるDBノードがなかったら、ダメなアクターに毒薬飲ませて死なせる
          log.error("All Db nodes broken, stopping.")
          self ! PoisonPill
        }
    }
  }

  /**
   * FileWatcherアクターのコンパニオンオブジェクト
   */
  object FileWatcher {
    case class NewFile(file: File, timeAdded: Long)
    case class SourceAbandoned(uri: String)
  }

  /**
   * FileWatcherアクター
   */
  class FileWatcher(source: String, databaseUrls: Vector[String]) extends Actor
                                                                  with ActorLogging
                                                                  with FileWatchingAbilities {
    // FileWatcher APIのソースURIを登録
    register(source)

    // ファイルの衝突が起きたら、再開する
    override def supervisorStrategy = OneForOneStrategy() {
      case _: CorruptedFileException => Resume
    }

    // LogProcessorアクターを生成
    val logProcessor = context.actorOf(
      LogProcessor.props(databaseUrls),
      LogProcessor.name
    )
    // 子であるlogProcessorを監視する
    context.watch(logProcessor)

    import FileWatcher._
    def receive = {
      // 新しいファイルのメッセージを受け取ったらLogProcessorアクターに渡してあげる
      case NewFile(file, _) =>
        LogProcessor ! LogProcessor.LogFile(file)

      // 監視ファイルが破棄されたら、毒薬飲んで死ぬ
      case SourceAbondoned(uri) if uri == source =>
        log.info(s"$uri abondoned, stopping file watcher.")
        self ! PoisonPill

      // logProcessorアクターがおっ死んだら、自分も毒薬飲んで心中
      case Terminated(`logProcessor`) =>
        log.info(s"Log processor terminated, stopping file watcher.")
        self ! PoisonPill
    }
  }

  /**
   * 独自例外を定義
   */
  // ディスクがクラッシュ
  @SerialVersionUID(1L)
  class DiskError(msg: String) extends Error(msg) with Serializable

  // ログファイルが破損しており、処理できない
  @SerialVersionUID(1L)
  class CorruptedFileException(msg: String, val file: File) extends Exception(msg) with Serializable

  @SerialVersionUID(1L)
  class DbBrokenConnectionException(msg: String) extends Exception(msg) with Serializable

  // DBノードが致命的にクラッシュした
  @SerialVersionUID(1L)
  class DbNodeDownException(msg: String) extends Exception(msg) with Serializable
}
