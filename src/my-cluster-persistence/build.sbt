
// akka-persistenceには、
// LevelDBのテスト目的でのみ利用できるローカルプラグインと
// 共有プラグインの
// ２種類のジャーナルプラグインがバンドルされている

// ローカルプラグインは単体アクターシステムからのみ利用可能で、
// 共有プラグインは複数アクターシステムから共有して利用可能である(クラスタ永続性をテストするのはこっちが便利)

lazy val akkaHttpVersion = "10.1.1"
lazy val akkaVersion    = "2.5.13"

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization    := "aia.persistence",
      scalaVersion    := "2.12.6"
    )),
    name := "my-persistence",
    resolvers ++= Seq(
      "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
      "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"
    ),
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor"           % akkaVersion,
      "com.typesafe.akka" %% "akka-stream"          % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence"     % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
      "org.iq80.leveldb"   % "leveldb"              % "0.7",
      "org.fusesource.leveldbjni" % "leveldbjni-all"% "1.8",
      "com.typesafe.akka" %% "akka-cluster"         % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-tools"   % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-sharding"% akkaVersion,
      "com.typesafe.akka" %% "akka-http"            % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-core"            % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,

      "com.typesafe.akka" %% "akka-http-testkit"    % akkaHttpVersion % Test,
      "com.typesafe.akka" %% "akka-testkit"         % akkaVersion     % Test,
      "com.typesafe.akka" %% "akka-stream-testkit"  % akkaVersion     % Test,
      "com.typesafe.akka" %% "akka-multi-node-testkit" % akkaVersion % Test,
      "org.scalatest"     %% "scalatest"            % "3.0.1"         % Test,
      "commons-io"        % "commons-io"           % "2.4",

      "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
      "ch.qos.logback"    % "logback-classic"      % "1.1.2"
    ),
    mainClass in Global := Some("aia.persistence.sharded.ShadedMain"),
    assemblyJarName in assembly := "persistence-examples.jar",
    fork := true, // ネイティブであるLevelDBを使う場合はforkして実行
    parallelExecution in Test := false // テストにおいて、fileの共有ジャーナルを用いるので、テスト並列実行無効化(レースコンディション回避)
  )
