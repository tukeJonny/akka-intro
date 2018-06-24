name := "akka-quickstart-scala"

version := "1.0"

scalaVersion := "2.12.6"

lazy val akkaVersion = "2.5.13"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka" %% "akka-remote"% akkaVersion,
  "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
  "com.typesafe.akka" %% "akka-multi-node-testkit" % akkaVersion,
  "ch.qos.logback"     % "logback-classic"         % "1.0.10",

  "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
  "org.scalatest" %% "scalatest" % "3.0.5" % "test"
)

// assembly jar settings
mainClass in Global := Some("aia.cluster.words.Main")
assemblyJarName in assembly := "words-node.jar"

// multi-jvm test settings
compile in MultiJvm := ((compile in MultiJvm) triggeredBy (compile in Test)).value
parallelExecution in Test := false
executeTests in Test := {
  val testResults = (executeTests in Test).value
  val multiNodeResults = (executeTests in MultiJvm).value
  val overall =
    if (testResults.overall.id < multiNodeResults.overall.id)
      multiNodeResults.overall
    else
      testResults.overall
    Tests.Output(overall,
      testResults.events ++ multiNodeResults.events,
      testResults.summaries ++ multiNodeResults.summaries)
}
