

object ApplicationBuild extends Build {

  val appName = "akka-raft"
  val appVersion = "1.0-SNAPSHOT"
  val appScalaVersion = "2.11.2"

  import Dependencies._
  import Resolvers._

  val debugInUse = SettingKey[Boolean]("debug-in-use", "debug is used")

  lazy val akkaRaft = Project(appName, file("."))
    .configs(MultiJvm)
    .settings(multiJvmSettings: _*)
    .settings(
      unmanagedSourceDirectories in Test += baseDirectory.value / "src" / "multi-jvm" / "scala",
      resolvers ++= additionalResolvers,
      libraryDependencies ++= generalDependencies,
      scalaVersion := appScalaVersion,
      scalacOptions ++= Seq("-unchecked","-deprecation")
    )

  lazy val multiJvmSettings = SbtMultiJvm.multiJvmSettings ++ Seq(
     // make sure that MultiJvm test are compiled by the default test compilation
     compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test),
     // disable parallel tests
     parallelExecution in Test := false,
     // make sure that MultiJvm tests are executed by the default test target
     executeTests in Test <<=
       (executeTests in Test, executeTests in MultiJvm) map {
         case (testResults, multiJvmResults)  =>
           val overall =
             if (testResults.overall.id < multiJvmResults.overall.id)
               multiJvmResults.overall
             else
               testResults.overall
           Tests.Output(overall,
             testResults.events ++ multiJvmResults.events,
             testResults.summaries ++ multiJvmResults.summaries)
     }
   )

}

object Dependencies {
    val akkaVersion = "2.3.4"
    val generalDependencies = Seq(
      "com.typesafe.akka" %% "akka-actor"     % akkaVersion,
      "com.typesafe.akka" %% "akka-slf4j"     % akkaVersion,

      "com.typesafe.akka" %% "akka-cluster"     % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-experimental" % akkaVersion,

      "com.typesafe.akka" %% "akka-testkit"            % akkaVersion % "test",
      "com.typesafe.akka" %% "akka-multi-node-testkit" % akkaVersion % "test",

      "org.mockito"        % "mockito-core"   % "1.9.5"     % "test",
      "org.scalatest"     %% "scalatest"      % "2.2.1"     % "test",
      "org.scala-lang"     % "scala-reflect"  % "2.11.2",
      "org.scala-lang"     % "scala-library"  % "2.11.2"
    )
  }

object Resolvers {
  val additionalResolvers = Seq(
    "typesafe snapshots" at "http://repo.akka.io/snapshots/"
  )

}