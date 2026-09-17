ThisBuild / scalaVersion := "3.3.1"

lazy val Versions = new {
  val pekkoStreamVersion = "1.1.3"
  val json4sVersion = "4.0.7"
  val typesafeConfigVersion = "1.4.2"
  val commonsStatsDistributions = "1.1"
  val commonsRngSampling = "1.6"
  val commonsRngSimple = "1.6"
  val scalaLoggingVersion = "3.9.5"
  val logbackClassicVersion = "1.3.5"
  val scalatestVersion = "3.2.19"
}

lazy val core = (project in file("core"))
  .settings(
    name := "stochastacy",
    version := "0.0.2",
    libraryDependencies ++= Seq(
      // Pekko -- streaming
      "org.apache.pekko" %% "pekko-stream" % Versions.pekkoStreamVersion,

      // JSON support (used by the demos' JSONL staging)
      "org.json4s" %% "json4s-jackson" % Versions.json4sVersion,

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

lazy val examples = (project in file("examples"))
  // the demos drive the AWS simulator in the `aws` module, which brings the `core` engine transitively.
  .dependsOn(aws)
  .settings(
    name := "stochastacy-examples",
    version := "0.0.2",

    // examples often want logging + runtime deps
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % Versions.logbackClassicVersion,
      "org.postgresql" % "postgresql" % "42.7.4",
      "org.scalatest" %% "scalatest" % Versions.scalatestVersion % "test",
      "com.h2database" % "h2" % "2.2.224" % "test"
    )
  )

lazy val aws = (project in file("aws"))
  .dependsOn(core)
  .settings(
    name := "stochastacy-aws",
    version := "0.0.2",

    // the AWS line's v2 components + example code; inherits the v2 engine + commons-rng via core
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % Versions.logbackClassicVersion,
      "org.apache.pekko" %% "pekko-stream-testkit" % Versions.pekkoStreamVersion % "test",
      "org.scalatest" %% "scalatest" % Versions.scalatestVersion % "test"
    )
  )

lazy val root = (project in file("."))
  .aggregate(core, examples, aws)
  .settings(
    publish / skip := true
  )


/* We don't need module-info.class files, and they screw up uberjar assembly, so here
 * is code that causes them to not be added to the assembled uberjar */
assembly / assemblyMergeStrategy := {
  case x if x.endsWith("module-info.class") => MergeStrategy.discard
  case x =>
    val oldStrategy = ( assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}
