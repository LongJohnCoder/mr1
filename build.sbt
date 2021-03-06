name := "SpinalTemplateSbt"

version := "1.0"

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.github.spinalhdl",
      scalaVersion := "2.11.6",
      version      := "0.1.0-SNAPSHOT"
    )),
    name := "superproject"
  ).dependsOn(spinalHdlSim,spinalHdlCore,spinalHdlLib)
lazy val spinalHdlSim = ProjectRef(file("/home/tom/projects/SpinalHDL"), "SpinalHDL-sim")
lazy val spinalHdlCore = ProjectRef(file("/home/tom/projects/SpinalHDL"), "SpinalHDL-core")
lazy val spinalHdlLib = ProjectRef(file("/home/tom/projects/SpinalHDL"), "SpinalHDL-lib")


addCompilerPlugin("org.scala-lang.plugins" % "scala-continuations-plugin_2.11.6" % "1.0.2")
scalacOptions += "-P:continuations:enable"
fork := true
