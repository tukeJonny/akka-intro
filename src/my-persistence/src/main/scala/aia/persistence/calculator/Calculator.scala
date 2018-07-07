package aia.persistence.calculator

import akka.actor._
import akka.persistence._

object Calculator {
  def props =
    Props(new Calculator)

  def name =
    "my-calculator"

  /**
   * 電卓コマンド(=Commandトレイト)関連のメッセージ
   */
  sealed trait Command
  /**
   * 制御命令
   */
  case object Clear extends Command // 消す
  case object PrintResult extends Command
  case object GetResult extends Command
  /**
   * 演算命令
   */
  case class Add(value: Double) extends Command // 足し算
  case class Subtract(value: Double) extends Command // 引き算
  case class Divide(value: Double) extends Command // 割り算
  case class Multiply(value: Double) extends Command // 掛け算

  /**
   * イベントのメッセージ
   * 電卓コマンドを実行することにより発生する更新イベント情報のメッセージがある
   * これがジャーナルログに記録されることで、永続化が実現できる
   * なお、これらイベントは電卓コマンドが `正しく実行された` ことを示している
   */
  sealed trait Event
  case object Reset extends Event // -> Clear
  case class Added(value: Double) extends Event // -> Add
  case class Subtracted(value: Double) extends Event // -> Subtract
  case class Divided(value: Double) extends Event // -> Divide
  case class Multiplied(value: Double) extends Event // -> Multiply

  /**
   * CalculationResult
   * 計算結果が保持される
   * 実際の演算処理はここで行われる
   * CalculationResultはstateとして保持される
   * 回復する際には、永続化された演算情報をもとにstateが更新される
   */
  case class CalculationResult(result: Double = 0) {
    def reset =
      copy(result = 0)
    def add(value: Double) =
      copy(result = this.result + value)
    def subtract(value: Double) =
      copy(result = this.result - value)
    def divide(value: Double) =
      copy(result = this.result / value)
    def multiply(value: Double) =
      copy(result = this.result * value)
  }
}

class Calculator extends PersistentActor
  with ActorLogging {

  import Calculator._

  // 永続時に一意に特定するために用いられる識別子
  def persistenceId =
    Calculator.name

  // 保持する状態
  var state = CalculationResult()

  /**
   * PersistentActorを継承した場合、通常のアクター継承時に実装するreceiveメソッドの代わりとして以下の２つのメソッドの実装責務
   * が生じる
   * * receiveCommand ... アクター回復時にメッセージを受け取るためのreceiveメソッド
   * * receiveRecover ... アクターの回復中に過去のイベントとスナップショットを受け取るためのreceiveメソッド
   *
   * (メッセージのCommandやEventといった名称は自由だが、receiveRecoverとreceiveCommandは名称固定で実装責務がある.
   * 公式ドキュメントを見ても、このメソッド名で実装している)
   * ちなみに、updateStateと言うメソッドを公式でも実装しているが、これは慣習的なもの（実装責務はない）
   *
   * 起動や再起動の際にこのメソッドは呼び出される
   */
  def receiveRecover: Receive = {
    // 過去のイベントやスナップショットを受信したら、状態を更新
    case event: Event =>
      updateState(event)
    // 回復完了(このメッセージは回復処理完了後に送信される)
    case RecoveryCompleted =>
      log.info("Calculator recovery completed")
  }

  def receiveCommand: Receive = {
    // 足し算命令
    // persist(イベント)(更新時ハンドラ) で呼び出してやると、persistメソッドの引数を永続化し、
    // 更新された際に更新時ハンドラが呼び出される
    // ここでは、更新時ハンドラを呼び出すことで、実際に自身の状態を更新している(1足された、5掛けられたなど)
    // ここでは状態(state)をCalculationResultで管理している
    case Add(value) =>
      persist(Added(value))(updateState)
    case Subtract(value) =>
      persist(Subtracted(value))(updateState)
    case Divide(value) =>
      if (value != 0) // ゼロ除算を防ぐ
        persist(Divided(value))(updateState)
    case Multiply(value) =>
      persist(Multiplied(value))(updateState)
    case PrintResult => // 結果を出力するだけで、状態は更新しない
      println(s"the result is: ${state.result}")
    case GetResult => // resultには結果の値が入っており、これを送信者に送り返す
      sender() ! state.result
    case Clear =>
      persist(Reset)(updateState)
  }

  /**
   * 多分、MySQLのバイナリログとかをイメージするとわかりやすい
   * 一旦WALして、それから結果が書き込まれるので、それと同じ原理
   *
   * このメソッドは、receiveCommandとreceiveRecoverメソッドで重複コード記述することを回避するためでもある
   */
  val updateState: Event => Unit = {
    case Reset =>
      state = state.reset
    case Added(value) =>
      state = state.add(value)
    case Subtracted(value) =>
      state = state.subtract(value)
    case Divided(value) =>
      state = state.divide(value)
    case Multiplied(value) =>
      state = state.multiply(value)
  }

}
