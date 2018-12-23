// Scala
val catsV = "1.4.0"
val catsEffectV = "1.1.0"
val fs2V = "1.0.0"
val http4sV = "0.20.0-M3"
val circeV = "0.10.0"
val doobieV = "0.6.0"
val log4catsV = "0.2.0"
val pureConfigV = "0.9.2"
val specs2V = "4.3.4"
// Java
val flyWayV = "5.1.4"
val logbackClassicV = "1.2.3"
val logstashEncoderV = "4.11"
val googleAuthV = "1.1.1"
// Banno
val vault4sV = "4.0.0-M2"
val zookeeperV = "2.0.0-M2"
val simpleHealthV = "2.0.0-M2"

organization := "com.banno"
scalaVersion := "2.12.6"
scalacOptions ++= Seq("-Xmax-classfile-name", "242")
publishArtifact in ThisBuild := false
cancelable in Scope.Global := true
addCompilerPlugin("org.spire-math" % "kind-projector"      % "0.9.8" cross CrossVersion.binary)
addCompilerPlugin("com.olegpy"     %% "better-monadic-for" % "0.2.4")
libraryDependencies ++= Seq(
//  "com.banno"             %% "vault4s"                   % vault4sV,
//  "com.banno"             %% "zookeeper-http4s"          % zookeeperV,
//  "com.banno"             %% "simple-health-prometheus"  % simpleHealthV,
  "org.typelevel"         %% "cats-core"                 % catsV,
  "org.typelevel"         %% "cats-effect"               % catsEffectV,
//  "co.fs2"                %% "fs2-io"                    % fs2V,
//  "org.http4s"            %% "http4s-dsl"                % http4sV,
//  "org.http4s"            %% "http4s-blaze-server"       % http4sV,
//  "org.http4s"            %% "http4s-blaze-client"       % http4sV,
//  "org.http4s"            %% "http4s-circe"              % http4sV,
//  "org.http4s"            %% "http4s-dropwizard-metrics" % http4sV,
//  "io.circe"              %% "circe-generic"             % circeV,
//  "io.circe"              %% "circe-parser"              % circeV,
//  "org.tpolecat"          %% "doobie-core"               % doobieV,
//  "org.tpolecat"          %% "doobie-hikari"             % doobieV,
//  "org.tpolecat"          %% "doobie-postgres"           % doobieV,
//  "io.chrisdavenport"     %% "log4cats-slf4j"            % log4catsV,
//  "com.github.pureconfig" %% "pureconfig"                % pureConfigV,
//  "org.flywaydb"          % "flyway-core"                % flyWayV,
//  "ch.qos.logback"        % "logback-classic"            % logbackClassicV,
//  "net.logstash.logback"  % "logstash-logback-encoder"   % logstashEncoderV,
  //"com.warrenstrange"          % "googleauth"                 % googleAuthV exclude ("commons-logging", "commons-logging"),
//  "io.github.tbrown1979"       %% "totp4s"                    % "0.0.1",
//    "org.tpolecat"               %% "doobie-specs2"             % doobieV % Test,
//  "org.specs2"                 %% "specs2-core"               % specs2V % Test,
//  "org.specs2"                 %% "specs2-scalacheck"         % specs2V % Test,
//  "org.typelevel"              %% "discipline"                % "0.8" % Test,
//  "com.github.alexarchambault" %% "scalacheck-shapeless_1.13" % "1.1.8" % Test
)
