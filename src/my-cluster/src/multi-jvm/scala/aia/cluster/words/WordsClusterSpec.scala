package aia.cluster
package words

import scala.concurrent.duration._

import akka.actor.Props

import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberUp}

import akka.testkit.ImplicitSender
import akka.remote.testkit.MultiNodeSpec
import JobReceptionist._

class WordsClusterSpecMultiJvmNode1 extends WordClusterSpec
class WordsClusterSpecMultiJvmNode2 extends WordClusterSpec
class WordsClusterSpecMultiJvmNode3 extends WordClusterSpec
class WordsClusterSpecMultiJvmNode4 extends WordClusterSpec

class WordsClusterSpec extends MultiNodeSpec(WordsClusterSpecConfig)
  with STMultiNodeSpec
  with ImplicitSender {


  import WordsClusterSpecConfig._

  def initialParticipants =
    role.size

  // WordsClusterSpecConfigを元にアドレスを解決
  val seedAddress = node(seed).address
  val masterAddress = node(master).address
  val worker1Address = node(worker1).address
  val worker2Address = node(worker2).address

  muteDeadLetters(classOf[Any])(system)

  "A Words cluster" must {

    // 10秒以内にクラスタを形成
    "form the cluster" in within(10 seconds) {
      // testActorにMemberUpをサブスクライブさせる(メンバの起動検知のため)
      Cluster(system).subscribe(testActor, classOf[MemberUp])
      expectMsgClass(classOf[CurrentClusterState])

      // seedをクラスターに参加させる 
      Cluster(system).join(seedAddress)
      // 4つのノードがすべて起動されるはず
      receiveN(4).map {
        case MemberUp(member) =>
          member.address
      }.toSet must be(Set(seedAddress,masterAddress,worker1Address,worker2Address))

      // finish
      Cluster(system).unsubscribe(testActor)
      enterBarrier("cluster-up")
    }

    // 10秒以内にジョブを完遂
    "execute a words job once the cluster is running" in within(10 seconds) {
      runOn(master) {
        val receptionist = system.actorOf(Props[JobReceptionist], "receptionist")
        receptionist ! JobRequest("job-1", List("some", "some very long text", "some long text"))
        expectMsg(JobSuccess("job-1", Map(
          "some" -> 3,
          "very" -> 1,
          "long" -> 2,
          "text" -> 2
        )))
        enterBarrier("job-done")
      }
    }

    // 障害が発生しても10秒以内に復帰
    "continue to process a job when failures occur" in within(10 seconds) {
      runOn(master) {
        val receptionist = system.actorSelection("/user/receptionist")
        receptionist ! JobRequest("job-2", List("some", "FAIL", "some very long text", "some long text"))
        expectMsg(JobSuccess("job-2", Map(
          "some" -> 3,
          "very" -> 1,
          "long" -> 2,
          "text" -> 2
        )))
        enterBarrier("job-done")
      }
    }
  }
}
