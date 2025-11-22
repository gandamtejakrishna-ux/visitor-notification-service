package kafka

import org.apache.pekko.actor.{ActorSystem, ActorRef}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.kafka.scaladsl.Consumer
import org.apache.pekko.kafka.{ConsumerSettings, Subscriptions}
import org.apache.kafka.common.serialization.StringDeserializer

import play.api.libs.json._

import com.typesafe.config.Config
import actors.{StartNotifications, CancelNotifications}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext

/**
 * Consumes visitor events from Kafka and forwards them to the notification supervisor.
 *
 * Listens to two topics (check-in and check-out), parses JSON messages,
 * and converts them into actor messages for further processing.
 */
class KafkaConsumerStream(
                           system: ActorSystem,
                           config: Config,
                           supervisor: ActorRef
                         )(implicit mat: Materializer, ec: ExecutionContext) {

  private val bootstrap = config.getString("kafka.bootstrap.servers")
  private val groupId = config.getString("kafka.group.id")
  private val topics = config.getStringList("kafka.topics")

  private val consumerSettings =
    ConsumerSettings(system, new StringDeserializer, new StringDeserializer)
      .withBootstrapServers(bootstrap)
      .withGroupId(groupId)

  /**
   * Starts Kafka consumption using Pekko Streams.
   *
   * Reads messages continuously from configured topics and processes them via handleMessage().
   */
  def run(): Unit = {
    val subscription = Subscriptions.topics(topics.get(0), topics.get(1))

    Consumer
      .plainSource(consumerSettings, subscription)
      .mapAsync(1) { msg =>
        handleMessage(msg.value())
      }
      .runForeach(_ => ())
  }

  /**
   * Parses an incoming Kafka JSON message and triggers the correct supervisor action.
   *
   * @param jsonStr Raw JSON string from Kafka.
   * @return Future[Unit] that completes after message handling.
   */
  private def handleMessage(jsonStr: String): Future[Unit] = Future {

    val js = Json.parse(jsonStr)
    val eventType = (js \ "eventType").asOpt[String]

    eventType match {

      //VISITOR CHECK-IN
      case Some("visitor.checkin") =>
        val visitId = (js \ "visitId").as[Long]

        val visitorJs = (js \ "visitor").as[JsObject]
        val firstName = (visitorJs \ "firstName").asOpt[String].getOrElse("")
        val lastName = (visitorJs \ "lastName").asOpt[String].getOrElse("")
        val visitorName = s"$firstName $lastName".trim
        val visitorEmail = (visitorJs \ "email").asOpt[String]

        val purpose = (js \ "purpose").asOpt[String].getOrElse("")

        val hostEmployeeId =
          (js \ "hostEmployeeId").asOpt[Long].getOrElse(0L) // must NOT be missing

        supervisor ! StartNotifications(
          jsonStr,
          visitId,
          visitorName,
          visitorEmail,
          purpose,
          hostEmployeeId
        )

        system.log.info(s"[Kafka] Sent StartNotifications for visit=$visitId")

      //VISITOR CHECK-OUT
      case Some("visitor.checkout") =>
        val visitId = (js \ "visitId").as[Long]
        supervisor ! CancelNotifications(visitId)
        system.log.info(s"[Kafka] Sent CancelNotifications for visit=$visitId")

      //UNKNOWN EVENTS
      case Some(other) =>
        system.log.warning(s"[Kafka] Unknown eventType: $other")

      case None =>
        system.log.warning(s"[Kafka] eventType missing in JSON")
    }
  }
}
