import sbt._
import Keys._

import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm

object ApplicationBuild extends Build {

  val appName = "akka-raft"
  val appVersion = "1.0-SNAPSHOT"
  val appScalaVersion = "2.10.2"

  import Dependencies._
  import Resolvers._


  lazy val akkaRaft = Project(appName, file("."))
    .configs(MultiJvm)
    .settings(multiJvmSettings: _*)
    .settings(
      resolvers ++= additionalResolvers,
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
       ((executeTests in Test), (executeTests in MultiJvm)) map {
         case ((_, testResults), (_, multiJvmResults))  =>
           val results = testResults ++ multiJvmResults
           (Tests.overall(results.values), results)
     }
   )

}

object Dependencies {
    val akkaVersion = "2.3-SNAPSHOT"
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

object Resolvers {
  val additionalResolvers = Seq(
    "typesafe snapshots" at "http://repo.akka.io/snapshots/"
  )

}