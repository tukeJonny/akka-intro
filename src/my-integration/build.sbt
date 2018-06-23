lazy val akkaHttpVersion = "10.1.1"
lazy val akkaVersion    = "2.5.13"
lazy val camelVersion = "2.17.7"
lazy val activeMQVersion = "5.4.1"

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization    := "aia.integration",
      scalaVersion    := "2.12.6"
    )),
    name := "my-integration",
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-xml" % "1.0.6",
      "com.typesafe.akka" %% "akka-http"            % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-core"       % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-xml"        % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-xml"        % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-stream"          % akkaVersion,
      "com.typesafe.akka" %% "akka-actor"           % akkaVersion,
      "com.typesafe.akka" %% "akka-camel"           % akkaVersion,
      "com.typesafe.akka" %% "akka-slf4j"           % akkaVersion,
      "org.scala-lang.modules" %% "scala-xml"       % "1.0.6",
      "ch.qos.logback"    %  "logback-classic"      % "1.1.3",
      "commons-io"        %  "commons-io"           % "2.0.1",
      "net.liftweb"       %  "lift-json_2.10"       % "3.0-M1",
      "io.arivera.oss" % "embedded-rabbitmq" % "1.2.1" % Test,

      "com.typesafe.akka" %% "akka-http-testkit"    % akkaHttpVersion % Test,
      "com.typesafe.akka" %% "akka-testkit"         % akkaVersion     % Test,
      "com.typesafe.akka" %% "akka-stream-testkit"  % akkaVersion     % Test,
      "org.scalatest"     %% "scalatest"            % "3.0.1"         % Test,
      "org.apache.camel"  %  "camel-mina"           % camelVersion    % Test,
      "org.apache.camel"  %  "camel-jetty"          % camelVersion    % Test,
      "org.apache.activemq" % "activemq-camel"      % activeMQVersion % Test,
      "org.apache.activemq" % "activemq-core"       % activeMQVersion % Test
    )
  )
