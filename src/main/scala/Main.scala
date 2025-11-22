import org.apache.pekko.actor.{ActorSystem, Props}
import org.apache.pekko.stream.SystemMaterializer
import services.{EmailService, NotificationRepo, WifiService}
import actors.NotificationSupervisor
import kafka.KafkaConsumerStream

/**
 * Application entry point for the Visitor Notification Service.
 *
 * Initializes the Actor system, loads configuration,
 * creates core services, and starts Kafka consumer + supervisor actor.
 */
object Main extends App {

  implicit val system: ActorSystem = ActorSystem("visitor-notification-system")
  implicit val mat = SystemMaterializer(system).materializer
  implicit val ec = system.dispatcher

  println("visitor-notification-service started.")

  val config = system.settings.config

  //Load DB config
  val jdbcUrl = config.getString("db.url")
  val dbUser  = config.getString("db.user")
  val dbPass  = config.getString("db.pass")

  //Construct Services
  val emailService = new EmailService(config)
  val wifiService  = new WifiService()
  val repo         = new NotificationRepo(jdbcUrl, dbUser, dbPass)

  //Supervisor with DI
  val supervisor = system.actorOf(
    Props(new NotificationSupervisor(emailService, wifiService, repo)),
    "supervisor"
  )

  //Kafka stream
  val consumer = new KafkaConsumerStream(system, config, supervisor)
  consumer.run()

  // Keep alive
  scala.io.StdIn.readLine()
  system.terminate()
}
