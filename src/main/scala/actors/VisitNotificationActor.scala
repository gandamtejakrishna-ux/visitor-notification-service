package actors

import org.apache.pekko.actor.{Actor, ActorRef, Props}
import org.apache.pekko.actor.{Cancellable, Props}

import scala.concurrent.duration._
import services.{EmailService, NotificationRepo, WifiService}

import scala.concurrent.ExecutionContext

/**
 * Coordinates all notifications for a single visitor's visit lifecycle.
 * Handles host email, visitor Wi-Fi provisioning, and security alerts with retries.
 */
object VisitNotificationActor {

  /**
   * Factory method to create VisitNotificationActor Props with its dependencies.
   */
    def props(
               visitId: Long,
               visitorName: String,
               visitorEmail: Option[String],
               purpose: String,
               hostEmployeeId: Long,
               emailService: EmailService,
               wifiService: WifiService,
               repo: NotificationRepo,
             )(implicit ec: ExecutionContext): Props =
      Props(new VisitNotificationActor(
        visitId,
        visitorName,
        visitorEmail,
        purpose,
        hostEmployeeId,
        emailService,
        wifiService,
        repo,
      ))

  /** Message: start all notifications (HOST, IT, SECURITY). */
    case object NotifyAll
  /** Message: cancel all pending notifications for this visit. */
    case object Cancel
    private case class Retry(role: String, attempt: Int)
}

class VisitNotificationActor(visitId: Long,
                             visitorName: String,
                             visitorEmail: Option[String],
                             purpose: String,
                             hostEmployeeId: Long,
                             emailService: EmailService,
                             wifiService: WifiService,
                             repo: NotificationRepo)(implicit ec: ExecutionContext) extends Actor {

  import VisitNotificationActor._

  private val maxRetries = 5
  private val baseBackoff = 2.seconds
  private var scheduled = Vector.empty[Cancellable]

  /**
   * Called when the actor starts.
   * Logs actor creation for this visit.
   */
  override def preStart(): Unit =
    context.system.log.info(s"[VisitNotificationActor] started for visit=$visitId")

  /**
   * Called when actor stops.
   * Cancels all scheduled retries and logs shutdown.
   */

  override def postStop(): Unit = {
    scheduled.foreach(_.cancel())
    context.system.log.info(s"[VisitNotificationActor] stopped for visit=$visitId")
  }

  /**
   * Handles all notification events:
   * - NotifyAll → trigger HOST, IT, SECURITY notification flows
   * - Cancel → stop retries and mark all roles as cancelled
   * - Retry → retry sending for a specific role with backoff
   */
  def receive: Receive = {
    case NotifyAll =>
      // ensure DB records exist
      repo.upsertPending(visitId, "HOST", "EMAIL")
      repo.upsertPending(visitId, "IT", "EMAIL")
      repo.upsertPending(visitId, "SECURITY", "INTERNAL")

      repo.isSent(visitId, "HOST").foreach { sent =>
        if (!sent) self ! Retry("HOST", 0)
      }
      repo.isSent(visitId, "IT").foreach { sent =>
        if (!sent) self ! Retry("IT", 0)
      }
      repo.isSent(visitId, "SECURITY").foreach { sent =>
        if (!sent) self ! Retry("SECURITY", 0)
      }

    case Cancel =>
      // cancel scheduled and mark cancelled
      scheduled.foreach(_.cancel())
      repo.markCancelled(visitId, "HOST")
      repo.markCancelled(visitId, "IT")
      repo.markCancelled(visitId, "SECURITY")
      context.stop(self)

    case Retry(role, attempt) =>
      role match {
        case "HOST" => attemptHost(attempt)
        case "IT"   => attemptIt(attempt)
        case "SECURITY" => attemptSecurity(attempt)
      }
  }

  /**
   * Schedules an exponential backoff retry for a specific notification role.
   *
   * @param role notification category (HOST, IT, SECURITY)
   * @param next next retry attempt number
   */
  private def scheduleRetry(role: String, next: Int): Unit = {
    val delay = baseBackoff * math.pow(2, next - 1).toLong
    val c = context.system.scheduler.scheduleOnce(delay, self, Retry(role, next))(context.dispatcher)
    scheduled = scheduled :+ c
    context.system.log.info(s"[VisitNotificationActor] scheduled retry #$next for $role visit=$visitId after $delay")
  }

  /**
   * Attempts to send host arrival email.
   * Retries on failure up to maxRetries using exponential backoff.
   */
  private def attemptHost(attempt: Int): Unit = {
    val to = s"host-${hostEmployeeId}@example.com"
    val subj = s"Visitor arrived: $visitorName"
    val body = s"Visitor $visitorName has arrived to meet you for $purpose."

    try {
      emailService.sendEmail(to, subj, body)
      repo.markSent(visitId, "HOST", Some("console"))
      context.system.log.info(s"[Host] Sent for visit=$visitId to $to")
    } catch {
      case ex: Throwable =>
        if (attempt >= maxRetries) repo.markFailed(visitId, "HOST", ex.getMessage)
        else scheduleRetry("HOST", attempt + 1)
    }
  }

  /**
   * Attempts to send visitor Wi-Fi credentials via email.
   * Uses WifiService helper method. Retries if email fails.
   */
  private def attemptIt(attempt: Int): Unit = {
    visitorEmail match {
      case Some(to) =>
        try {
          // Use the reusable method
          wifiService.sendWifiCredentials(emailService, to)

          repo.markSent(visitId, "IT", Some("console"))
          context.system.log.info(s"[IT] Wi-Fi sent for visit=$visitId to $to")

        } catch {
          case ex: Throwable =>
            if (attempt >= maxRetries)
              repo.markFailed(visitId, "IT", ex.getMessage)
            else
              scheduleRetry("IT", attempt + 1)
        }

      case None =>
        repo.markFailed(visitId, "IT", "no visitor email")
    }
  }

  /**
   * Sends internal security alert email.
   * Retries on failure until maxRetries is reached.
   */
  private def attemptSecurity(attempt: Int): Unit = {
    try {
      val securityEmail = context.system.settings.config.getString("security.email")

      val subject = s"Visitor Entry Alert (Visit $visitId)"
      val body =
        s"""
           |Visitor: $visitorName
           |Visit ID: $visitId
           |Purpose: $purpose
           |Host Employee ID: $hostEmployeeId
           |
           |This is an automated security entry notification.
       """.stripMargin

      emailService.sendEmail(securityEmail, subject, body)

      repo.markSent(visitId, "SECURITY", Some("email"))
      context.system.log.info(s"[Security] Email sent for visit=$visitId")

    } catch {
      case ex: Throwable =>
        if (attempt >= maxRetries) repo.markFailed(visitId, "SECURITY", ex.getMessage)
        else scheduleRetry("SECURITY", attempt + 1)
    }
  }
}
