import sbt._
import Keys._

object ApplicationBuild extends Build {

  val appName = "akka-raft"
  val appVersion = "1.0-SNAPSHOT"
  val appScalaVersion = "2.10.2"

  import Dependencies._
  import Resolvers._


  val webcms = Project(appName, file("."))
    .settings(
      resolvers ++= additionalResolvers,
      libraryDependencies ++= generalDependencies,
      scalaVersion := appScalaVersion
    )

}

object Dependencies {
    val akkaVersion = "2.3-SNAPSHOT"
    val generalDependencies = Seq(
      "com.typesafe.akka" %% "akka-actor"     % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster"   % akkaVersion,
      "com.typesafe.akka" %% "akka-slf4j"     % akkaVersion,
      "com.typesafe.akka" %% "akka-testkit"   % akkaVersion % "test",
      "org.scalatest"     %% "scalatest"      % "2.0"       % "test"
    )
  }

object Resolvers {
  val additionalResolvers = Seq(
    "typesafe snapshots" at "http://repo.akka.io/snapshots/"
  )

}