
val dottyVersion = "0.11.0-RC1"

lazy val root = project
	.in(file("."))
	.settings(
	name := "dotty-simple",
	version := "0.1.0",
	scalaVersion := dottyVersion,

libraryDependencies ++= Seq(
	"ch.epfl.lamp" % "dotty_0.11" % dottyVersion,
	"ch.epfl.lamp" % "dotty_0.11" % dottyVersion % "test->runtime",
	"com.novocode" % "junit-interface" % "0.11" % "test"
	)
	)

