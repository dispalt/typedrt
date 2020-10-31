ThisBuild / scalaVersion := Library.Scala

Global / semanticdbEnabled := true

Global / cancelable := true

updateConfiguration := updateConfiguration.value.withMissingOk(true)

Global / testFrameworks += new TestFramework("weaver.framework.TestFramework")

lazy val goodcover =
  project
    .in(file("."))
    .aggregate(typedrt, `typedrt-models`, `typedrt-schedule`)

/**
  * The functional project, basically niceities local to this repo.
  */
lazy val fxn = project
  .in(file("fxn"))
  .settings(Library.Projects.fxn)

lazy val typedrt = project
  .in(file("typedrt"))
  .settings(Library.Projects.typedrt, Build.pbSettings)
  .dependsOn(fxn, `typedrt-models`)

lazy val `typedrt-schedule` = project
  .in(file("typedrt-schedule"))
  .dependsOn(typedrt % "compile->compile;test->test")

lazy val `typedrt-models` = project
  .in(file("typedrt-models"))
  .settings(Library.Projects.`typedrt-models`)
  .settings(Build.pbSettings)


lazy val `typedrt-zproc` = project
  .in(file("typedrt-zproc"))
  .settings(Library.Projects.`typedrt-zproc`)
  .settings(Build.pbSettings)
