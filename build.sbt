name := "visitor-notification-service"
version := "1.0"
scalaVersion := "2.13.12"

libraryDependencies ++= Seq(
  "org.apache.pekko" %% "pekko-actor" % "1.0.2",
  "org.apache.pekko" %% "pekko-stream" % "1.0.2",
  "org.apache.pekko" %% "pekko-slf4j" % "1.0.2",

  // Kafka Connector (Pekko Kafka)
  "org.apache.pekko" %% "pekko-connectors-kafka" % "1.0.0",

  // Logging
  "ch.qos.logback" % "logback-classic" % "1.2.13"
)
libraryDependencies += "com.typesafe.play" %% "play-json" % "2.9.4"
libraryDependencies += "com.zaxxer" % "HikariCP" % "5.0.1"
libraryDependencies += "mysql" % "mysql-connector-java" % "8.0.33"
libraryDependencies += "org.eclipse.angus" % "angus-mail" % "2.0.2"
