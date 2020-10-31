import sbt.Keys._
import sbt._
import sbtprotoc.ProtocPlugin.autoImport.PB

object Library {

  final val Scala213 = "2.13.3"
  final val Scala    = Scala213

  final val KindProjector = "0.11.0"

  private object Version {

    final val Akka            = "2.6.10"
    final val AkkaStreamC     = "0.10"
    final val AkkaHttp        = "10.1.11"
    final val AkkaCassandra   = "0.102" // 1.0 has a lot of changes
    final val CassandraExtras = "3.1.0"
    final val Cats            = "2.2.0"
    final val CatsMtl         = "1.0.0"
    final val CatsEffect      = "2.2.0"
    final val Circe           = "0.13.0"
    final val CirceDeriv      = "0.13.0-M2"
    final val Chill           = "0.9.5"
    final val Monix           = "3.2.1"
    final val Monocle         = "2.0.3"
    final val Netty           = "4.1.43.Final"
    final val ScalaLogging    = "3.9.2"
    final val ScalaPB         = scalapb.compiler.Version.scalapbVersion
    final val ScalaTest       = "3.1.2"
    final val ScalaCheck      = "1.14.3"
    final val Slf4j           = "1.7.22"
    final val Sourcecode      = "0.1.3"
    final val TaglessRedux    = "0.3.6"
    final val WeaverTest      = "0.5.0"
    final val Zio             = "1.0.3"
  }

  // ~~~~~~~~~~~~~~~~~~~~~~~~~~
  // Tests!
  // ~~~~~~~~~~~~~~~~~~~~~~~~~~
  val scalaTest = "org.scalatest" %% "scalatest" % Version.ScalaTest

  // ~~~~~~~~~~~~~~~~~
  // Core Deps
  // ~~~~~~~~~~~~~~~~~
  val scalaLogging     = "com.typesafe.scala-logging" %% "scala-logging" % Version.ScalaLogging
  val slf4jApi         = "org.slf4j" % "slf4j-api" % Version.Slf4j
  val nettyLinux       = "io.netty" % "netty-transport-native-epoll" % Version.Netty classifier "linux-x86_64"
  val nettyCommonLinux = "io.netty" % "netty-transport-native-unix-common" % Version.Netty

  // https://mvnrepository.com/artifact/net.java.dev.jna/jna

  // ~~~~~~~~~~~~~~~~~
  // Functional
  // ~~~~~~~~~~~~~~~~~
  val cats       = libraryDependencies += "org.typelevel" %% "cats-core" % Version.Cats
  val catsEffect = libraryDependencies += "org.typelevel" %% "cats-effect" % Version.CatsEffect
  val catsFree   = "org.typelevel" %% "cats-free" % Version.Cats
  val catsMtl    = "org.typelevel" %% "cats-mtl" % Version.CatsMtl

  val sourcecode = "com.lihaoyi" %% "sourcecode" % Version.Sourcecode
  val monocle    = "com.github.julien-truffaut" %% "monocle-macro" % Version.Monocle

  val monix = libraryDependencies += "io.monix" %% "monix" % Version.Monix

  val zio           = "dev.zio" %% "zio" % Version.Zio
  val zioTest       = "dev.zio" %% "zio-test" % Version.Zio % Test
  val zioTestSbt    = "dev.zio" %% "zio-test-sbt" % Version.Zio % Test
  val zioTestMag    = "dev.zio" %% "zio-test-magnolia" % Version.Zio % Test // optional
  val weaverTest    = "com.disneystreaming" %% "weaver-framework" % Version.WeaverTest % Test
  val weaverZioTest = "com.disneystreaming" %% "weaver-zio" % Version.WeaverTest % Test

  // ~~~~~~~~~~~~~~~~~
  // Data
  // ~~~~~~~~~~~~~~~~~
  val `akka-cassandra`      = "com.typesafe.akka" %% "akka-persistence-cassandra" % Version.AkkaCassandra
  val `akka-cassandra-test` = "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % Version.AkkaCassandra
  val `cassandra-extras`    = "com.datastax.cassandra" % "cassandra-driver-extras" % Version.CassandraExtras

  // ~~~~~~~~~~~~~~~~~
  // Serialization
  // ~~~~~~~~~~~~~~~~~
  object Circe {

    val libs = libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % Version.Circe,
      "io.circe" %% "circe-derivation" % Version.CirceDeriv,
      "io.circe" %% "circe-parser" % Version.Circe,
    )
  }
  val scalaPbProto = "com.thesamet.scalapb" %% "scalapb-runtime" % Version.ScalaPB % "protobuf"
  val scalaPbGrpc  = "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % Version.ScalaPB
  val chill        = "com.twitter" %% "chill-akka" % Version.Chill

  val pbDependencies: Seq[Def.Setting[_]] = Seq(
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime" % Version.ScalaPB,
      "com.thesamet.scalapb" %% "scalapb-runtime" % Version.ScalaPB % "protobuf"
    )
  )

  // ~~~~~~~~~~~~~~~~~
  // Akka Actor and Friends
  // ~~~~~~~~~~~~~~~~~
  val akka        = "com.typesafe.akka" %% "akka-actor" % Version.Akka
  val akkaPers    = "com.typesafe.akka" %% "akka-persistence" % Version.Akka
  val akkaPQuery  = "com.typesafe.akka" %% "akka-persistence-query" % Version.Akka
  val akkaC       = "com.typesafe.akka" %% "akka-cluster" % Version.Akka
  val akkaCS      = "com.typesafe.akka" %% "akka-cluster-metrics" % Version.Akka
  val akkaCSM     = "com.typesafe.akka" %% "akka-cluster-sharding" % Version.Akka
  val akkaCST     = "com.typesafe.akka" %% "akka-cluster-typed" % Version.Akka
  val akkaSlf4j   = "com.typesafe.akka" %% "akka-slf4j" % Version.Akka
  val akkaTestKit = "com.typesafe.akka" %% "akka-testkit" % Version.Akka

  val akkaHttpCore = "com.typesafe.akka" %% "akka-http-core" % Version.AkkaHttp
  val akkaHttp     = "com.typesafe.akka" %% "akka-http" % Version.AkkaHttp
  val akkaHttpXml  = "com.typesafe.akka" %% "akka-http-xml" % Version.AkkaHttp
  val akkaStreamC  = "com.typesafe.akka" %% "akka-stream-contrib" % Version.AkkaStreamC

  // ~~~~~~~~~~~~~~
  // Web tests
  // ~~~~~~~~~~~~~~~~
  val scalaTestE = libraryDependencies += "org.scalatest" %% "scalatest" % Version.ScalaTest % "test"
  val scalaCheck = libraryDependencies += "org.scalacheck" %% "scalacheck" % Version.ScalaCheck % "test"

  // ~~~~~~~~~~~~~~~~~~
  // Ammonite for prod!
  // ~~~~~~~~~~~~~~~~~~

  // ~~~~~~~~~~~~~~~~
  // Tagless New
  // ~~~~~~~~~~~~~~~~
  val `tagless-redux` =
    "com.dispalt" %% "tagless-redux-macros" % Version.TaglessRedux exclude ("com.typesafe.akka", "akka-actor_2.12")

  val `tagless-redux-kencoder` =
    "com.dispalt" %% "tagless-redux-encoder-akka" % Version.TaglessRedux exclude ("com.typesafe.akka", "akka-actor_2.12")

  val `tagless-redux-kryo` =
    "com.dispalt" %% "tagless-redux-encoder-kryo" % Version.TaglessRedux exclude ("com.typesafe.akka", "akka-actor_2.12")

  object Projects {

    val fxn = Seq(
      libraryDependencies ++= Seq(
        Library.catsFree,
        Library.slf4jApi,
        Library.scalaTest % Test,
        Library.`tagless-redux`,
        Library.zio,
      ),
      Library.cats,
      Library.catsEffect,
      Library.Circe.libs,
      Library.monix,
    )

    val typedrt = Seq(
      libraryDependencies ++= Seq(
        Library.`akka-cassandra`,
        Library.`cassandra-extras`,
        Library.akkaC,
        Library.akkaCS,
        Library.akkaCSM,
        Library.akkaPQuery,
        Library.akkaPers,
        Library.akkaSlf4j,
        Library.scalaLogging,
        Library.catsMtl,
        Library.scalaPbProto,
        Library.`tagless-redux`,
        Library.`tagless-redux-kencoder`,
        Library.`tagless-redux-kryo`,
        Library.monocle,
        Library.`akka-cassandra-test` % Test,
        Library.akkaTestKit % Test,
        Library.scalaTest % Test,
        Library.zioTestSbt,
        Library.zioTest,
        Library.zioTestMag,
//        Library.weaverTest,
//        Library.weaverZioTest,
      ),
      Library.cats,
      Library.catsEffect,
      Library.monix,
    )

    val `typedrt-models` = libraryDependencies ++= Seq(Library.scalaPbProto)

    val `typedrt-zproc` = libraryDependencies ++= Seq(
      Library.akkaC,
      Library.akkaCS,
      Library.akkaCSM,
      Library.`tagless-redux`,
      Library.`tagless-redux-kencoder`,
      Library.`tagless-redux-kryo`,
      Library.zio,
      Library.zioTestSbt,
      Library.zioTest,
      Library.zioTestMag,
    )
  }

}
