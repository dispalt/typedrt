import sbt.Keys._
import sbt.plugins.JvmPlugin
import sbt.{Compile, Def, IO, Test, _}
import sbtprotoc.ProtocPlugin.autoImport.PB

import scala.sys.process._

object Build extends AutoPlugin {

  override def requires =
    JvmPlugin

  override def trigger = allRequirements

  object autoImport {

  }

  import autoImport._

  override def projectSettings: Seq[Setting[_]] =
    Seq(
      // Core settings
      organization := "com.goodcover.core",
      scalaVersion := Version.Scala213,
      // format: off
        scalacOptions ++= Seq(
          "-deprecation",
          "-encoding", "UTF-8",
          "-explaintypes",                     // Explain type errors in more detail.
          "-feature",
          "-language:_",
          "-language:existentials",            // Existential types (besides wildcard types) can be written and inferred
          "-language:experimental.macros",     // Allow macro definition (besides implementation and application)
          "-language:higherKinds",             // Allow higher-kinded types
          "-language:implicitConversions",     // Allow definition of implicit functions called views
          "-target:jvm-1.8",
          "-unchecked",
          "-Xlint:adapted-args",               // Warn if an argument list is modified to match the receiver.
//          "-Xlint:by-name-right-associative",  // By-name parameter of right associative operator.
          "-Xlint:constant",                   // Evaluation of a constant arithmetic expression results in an error.
          "-Xlint:delayedinit-select",         // Selecting member of DelayedInit.
          "-Xlint:doc-detached",               // A Scaladoc comment appears to be detached from its element.
          "-Xlint:inaccessible",               // Warn about inaccessible types in method signatures.
          "-Xlint:infer-any",                  // Warn when a type argument is inferred to be `Any`.
          "-Xlint:missing-interpolator",       // A string literal appears to be missing an interpolator id.
//          "-Xlint:nullary-override",           // Warn when non-nullary `def f()' overrides nullary `def f'.
          "-Xlint:nullary-unit",               // Warn when nullary methods return Unit.
          "-Xlint:option-implicit",            // Option.apply used implicit view.
          "-Xlint:package-object-classes",     // Class or object defined in package object.
          "-Xlint:poly-implicit-overload",     // Parameterized overloaded implicit methods are not visible as view bounds.
          "-Xlint:private-shadow",             // A private field (or class parameter) shadows a superclass field.
          "-Xlint:stars-align",                // Pattern sequence wildcard must align with sequence component.
          "-Xlint:type-parameter-shadow",      // A local type parameter shadows a type already in scope.
//          "-Xlint:unsound-match",              // Pattern match may not be typesafe.
          "-Yinline-warnings",
//          "-Yno-adapted-args",                 // Do not adapt an argument list (either by inserting () or creating a tuple) to match the receiver.
//          "-Ypartial-unification",             // Enable partial unification in type constructor inference
          "-Ywarn-dead-code",                  // Warn when dead code is identified.
          "-Ywarn-extra-implicit",             // Warn when more than one implicit parameter section is defined.
//          "-Ywarn-inaccessible",               // Warn about inaccessible types in method signatures.
//          "-Ywarn-infer-any",                  // Warn when a type argument is inferred to be `Any`.
//          "-Ywarn-nullary-override",           // Warn when non-nullary `def f()' overrides nullary `def f'.
//          "-Ywarn-nullary-unit",               // Warn when nullary methods return Unit.
          "-Ywarn-numeric-widen",              // Warn when numerics are widened.
          "-Ywarn-unused:implicits",           // Warn if an implicit parameter is unused.
  //          "-Ymacro-debug-lite"                 // Warn when non-Unit expression results are unused.
  //        "-Ywarn-unused:imports",             // Warn if an import selector is not referenced.
  //        "-Ywarn-unused:locals",              // Warn if a local definition is unused.
  //        "-Ywarn-unused:params",              // Warn if a value parameter is unused.
  //        "-Ywarn-unused:patvars",             // Warn if a variable bound in a pattern is unused.
  //        "-Ywarn-unused:privates",            // Warn if a private member is unused.
          "-Ywarn-value-discard",                // Warn when non-Unit expression results are unused.
//          "-Ystatistics",                      // Output statistics
//          "-Yrangepos",                        // required by SemanticDB compiler plugin
          "-Ypatmat-exhaust-depth", "40",       // Add some extra patmatch love
          "-Ymacro-annotations",
        ),
        // format: on
      resolvers ++= Seq(
        Resolver.mavenLocal,
        Resolver.jcenterRepo
//        "Goodcover Releases" at "s3://s3-us-west-1.amazonaws.com/repo.goodcover.com/releases"
      ),
      publishTo := {
        if (isSnapshot.value)
          Some("Goodcover Snapshots" at "s3://s3-us-west-2.amazonaws.com/repo.goodcover.com/snapshots")
        else
          Some("Goodcover Releases" at "s3://s3-us-west-2.amazonaws.com/repo.goodcover.com/releases")
      },
      unmanagedSourceDirectories.in(Compile) := Vector(scalaSource.in(Compile).value),
      unmanagedSourceDirectories.in(Test) := Vector(scalaSource.in(Test).value),
      addCompilerPlugin("org.typelevel" % "kind-projector" % Version.KindProjector cross CrossVersion.full),
      libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided",
      // Compiling for some reason does not work with this... Investigate later.
      publishArtifact in (Compile, packageDoc) := false,
      sources in (Compile, doc) := Seq.empty,
      crossScalaVersions := Seq(Version.Scala)
    ) ++ extras

  lazy val extras: Seq[Setting[_]] = Seq(scalacOptions := {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 11)) => scalacOptions.value.filterNot(_.startsWith("-Ywarn")).filterNot(_.startsWith("-Xlint"))
      case _             => scalacOptions.value.filterNot(_.equals("-Yinline-warnings"))
    }
  })

   lazy val pbSettings: Seq[Def.Setting[_]] = Seq(
     PB.includePaths in Compile += file("proto/src/main/protobuf"),
     // Moved to a sub directory so it doesn't mess with other files.
     PB.targets in Compile := Seq(
       scalapb.gen(singleLineToProtoString = true, lenses = false) -> (sourceManaged in Compile).value / "protobuf"
     )
   )

}
