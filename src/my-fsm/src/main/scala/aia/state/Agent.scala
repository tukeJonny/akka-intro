package aia.state

import akka.agent.Agent
import akka.actor.ActorSystem
import concurrent.Await
import concurrent.duration._
import akka.util.Timeout


// Akkaのエージェントを用いて、他のアクターやインスタンスと共有できる
// 本の統計情報を作る
//
// ここで残念なお知らせなのだが・・・
// Agentは今後廃止予定らしく、Akka Typedで似たようなことができるそう
// だが、Akka2.5でもまだ確定的な発表はなかったらしく、動向を見守る必要がありそう


// 各々の書籍毎用意される統計情報
case class BookStatics(val nameBook: String, nrSold: Int)

// こいつに問い合わせることで、BookStaticsを取得できる
// 変更や現在の書籍統計をチェックするためのシーケンス番号を持つ
case class StateBookStatics(val sequence: Long, books: Map[String,BookStatics])

class BookStaticsMgr(system: ActorSystem) {
  implicit val ex = system.dispatcher
  val stateAgent = Agent(new StateBookStatics(0, Map()))

  def addBooksSold(book: String, nrSold: Int): Unit = {
    // ちょっと複雑に見えるが、
    // stateAgent send (即時関数) みたいな感じらしい
    // この即時関数は古い状態から新しい状態を得る。
    // 新しい状態を作るために、nrSoldとbookが自由変数になっており、即時関数内で
    // 束縛される
    // 状態を共有するのにロックをこちら側で気にしなくていいのがAgentのウリなので、即時関数を渡して任せるというのがスタイルに合ってる
    stateAgent send (oldState => {
      // 古い状態から本を取得し、存在する場合は売った数を更新
      // 存在しない場合はBookStaticsを新たに作って返す
      val bookStat = oldState.books.get(book) match {
        case Some(bookState) =>
          bookState.copy(nrSold = bookState.nrSold + nrSold)
        case None =>
          new BookStatics(book, nrSold)
      }
      // 本の名前からBookStatics(=bookStat)への参照をMapに追加し、
      // シーケンス番号も更新して返す
      oldState.copy(oldState.sequence + 1, oldState.books + (book -> bookStat))
    })
  }

  def addBooksSoldAndReturnNewState(book: String, nrSold: Int): StateBookStatics = {
    // 古い状態を更新する
    // sendではなくalterを使うといい
    val future = stateAgent alter (oldState => {
      val bookStat = oldState.books.get(book) match {
        case Some(bookState) =>
          bookState.copy(nrSold = bookState.nrSold + nrSold)
        case None =>
          new BookStatics(book, nrSold)
      }
      oldState.copy(oldState.sequence + 1, oldState.books + (book -> bookStat))
    })
    // 1秒結果を待つ
    Await.result(future, 1 second)
  }

  def getStateBookStatics(): StateBookStatics = {
    stateAgent.get()
  }
}
