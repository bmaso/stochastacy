ThisBuild / scalaVersion := "3.3.1"

lazy val Versions = new {
  val pekkoStreamVersion = "1.1.3"
  val pekkoHttpVersion = "1.1.0"
  val json4sVersion = "4.0.7"
  val pekkoHttpJsonVersion = "3.0.0"
  val typesafeConfigVersion = "1.4.2"
  val commonsStatsDistributions = "1.1"
  val commonsRngSampling = "1.6"
  val commonsRngSimple = "1.6"
  val scalaLoggingVersion = "3.9.5"
  val logbackClassicVersion = "1.3.5"
  val scalatestVersion = "3.2.19"
}

lazy val root = (project in file("."))
  .settings(
    name := "stochastacy",
    version := "0.0.1",
    libraryDependencies ++= Seq(
      // Pekko -- streaming, actors, and HTTP server
      "org.apache.pekko" %% "pekko-stream" % Versions.pekkoStreamVersion,
      "org.apache.pekko" %% "pekko-http" % Versions.pekkoHttpVersion,

      // JSON and JSON-Pekko support
      "org.json4s" %% "json4s-jackson" % Versions.json4sVersion,
      "com.github.pjfanning" %% "pekko-http-json4s" % Versions.pekkoHttpJsonVersion, // JSON support

      // Typesafe config for application configuration
      "com.typesafe" % "config" % Versions.typesafeConfigVersion, // Config loading

      // Apache commons statistics packages
      "org.apache.commons" % "commons-statistics-distribution" % Versions.commonsStatsDistributions,
                                                                  // For generating data
      "org.apache.commons" % "commons-rng-sampling" % Versions.commonsRngSampling,
                                                                  // for pseudo-random number generation
      "org.apache.commons" % "commons-rng-simple" % Versions.commonsRngSimple,
                                                                  // Apache-provided RNG algos

      // Logging
      "com.typesafe.scala-logging" %% "scala-logging" % Versions.scalaLoggingVersion,
      "ch.qos.logback" % "logback-classic" % Versions.logbackClassicVersion,

      // Testing
      "org.apache.pekko" %% "pekko-stream-testkit" % Versions.pekkoStreamVersion % "test",
      "org.scalatest" %% "scalatest" % Versions.scalatestVersion % "test"
    )
  )

/* We don't need module-info.class files, and they screw up uberjar assembly, so here
 * is code that causes them to not be added to the assembled uberjar */
assembly / assemblyMergeStrategy := {
  case x if x.endsWith("module-info.class") => MergeStrategy.discard
  case x =>
    val oldStrategy = ( assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}
