package akka.testkit

import java.io.File
import com.typesafe.config._

import scala.util._

import akka.actor._
import akka.persistence._
import org.scalatest._

import org.apache.commons.io.FileUtils

/**
 * testkitに組み込む、akka-persistenceのテストキットを自前実装
 * 電卓でも使われてる
 */

abstract class PersistenceSpec(system: ActorSystem) extends TestKit(system)
  with ImplicitSender
  with WordSpecLike
  with Matchers
  with BeforeAndAfterAll
  with PersistenceCleanup {

  def this(name: String, config: Config) =
    this(ActorSystem(name, config))

  override protected def beforeAll() = deleteStorageLocations()
  override protected def afterAll() = {
    deleteStorageLocations()
    TestKit.shutdownActorSystem(system)
  }

  def killActors(actors: ActorRef*) = {
    actors.foreach { actor =>
      watch(actor)
      system.stop(actor)
      expectTerminated(actor)
      Thread.sleep(1000) // persistenceId(=アクター名)重複を防ぐために待つ
    }
  }
}

trait PersistenceCleanup {
  def system: ActorSystem

  val storageLocations = List(
    "akka.persistence.journal.leveldb.dir",
    "akka.persistence.journal.leveldb-shared.store.dir",
    "akka.persistence.snapshot-store.loca.dir").map { s => 
      new File(system.settings.config.getString(s))
    }

  /**
   * LevelDBのジャーナルによって作成されたディレクトリを削除
   * デフォルトのスナップショットジャーナルによって作成されるディレクトリも削除する
   */
  def deleteStorageLocations(): Unit = {
    storageLocations.foreach( dir =>
      Try(FileUtils.deleteDirectory(dir))
    )
  }
}
