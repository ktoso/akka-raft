import sbt._
import Keys._

import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm

object ApplicationBuild extends Build {

  val appName = "akka-raft"
  val appVersion = "1.0-SNAPSHOT"
  val appScalaVersion = "2.10.4"

  import Dependencies._

  val debugInUse = SettingKey[Boolean]("debug-in-use", "debug is used")

  lazy val akkaRaft = Project(appName, file("."))
    .configs(MultiJvm)
    .settings(multiJvmSettings: _*)
    .settings(
      libraryDependencies ++= generalDependencies,
      scalaVersion := appScalaVersion
    )

  lazy val multiJvmSettings = SbtMultiJvm.multiJvmSettings ++ Seq(
     // make sure that MultiJvm test are compiled by the default test compilation
     compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test),
     // disable parallel tests
     parallelExecution in Test := false,
     // make sure that MultiJvm tests are executed by the default test target
     executeTests in Test <<=
       ((executeTests in Test), (executeTests in MultiJvm)).map{
         case (outputOfTests, outputOfMultiJVMTests)  =>
           Tests.Output(Seq(outputOfTests.overall, outputOfMultiJVMTests.overall).sorted.reverse.head, outputOfTests.events ++ outputOfMultiJVMTests.events, outputOfTests.summaries ++ outputOfMultiJVMTests.summaries)
      }
   )

}

object Dependencies {
    val akkaVersion = "2.3.6"
    val generalDependencies = Seq(
      "com.typesafe.akka" %% "akka-actor"     % akkaVersion,
      "com.typesafe.akka" %% "akka-slf4j"     % akkaVersion,

      "com.typesafe.akka" %% "akka-cluster"     % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-experimental" % akkaVersion,

      "com.typesafe.akka" %% "akka-testkit"            % akkaVersion % "test",
      "com.typesafe.akka" %% "akka-multi-node-testkit" % akkaVersion % "test",

      "org.mockito"        % "mockito-core"   % "1.9.5"     % "test",
      "org.scalatest"     %% "scalatest"      % "2.0"       % "test"
    )
  }
