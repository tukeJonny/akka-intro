package aia.stream

import java.nio.file.{ Path, Paths }

object FileArg {
  def shellExpanded(path: String): Path = {
    if (path.startsWith("~/")) {
      // path.drop(2)によって、 `~/` を消す
      // で、user.homeに設定されたホームディレクトリへの絶対パスとJoinする
      Paths.get(System.getProperty("user.home"), path.drop(2))
    } else {
      Paths.get(path)
    }
  }
}
