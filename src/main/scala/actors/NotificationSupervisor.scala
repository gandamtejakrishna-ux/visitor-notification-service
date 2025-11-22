package actors

import org.apache.pekko.actor.{Actor, ActorRef, Props}
import scala.collection.mutable
import services._
import scala.concurrent.ExecutionContext

/**
 * Message to begin all notifications for a visitor's check-in event.
 *
 * @param checkinJson     Raw JSON payload received from Kafka.
 * @param visitId         Unique visit identifier.
 * @param visitorName     Name of the visitor.
 * @param visitorEmail    Email of the visitor (if provided).
 * @param purpose         Purpose of visit.
 * @param hostEmployeeId  Host employee ID.
 */
case class StartNotifications(
                               checkinJson: String,
                               visitId: Long,
                               visitorName: String,
                               visitorEmail: Option[String],
                               purpose: String,
                               hostEmployeeId: Long
                             )
/**
 * Message to stop and cancel all notifications linked to a visit.
 *
 * @param visitId Visit whose notification workflow must be stopped.
 */
case class CancelNotifications(visitId: Long)

/**
 * Supervisor actor responsible for managing notification workflows.
 *
 * Spawns a VisitNotificationActor per visit, tracks active notification flows,
 * and handles cancellation requests.
 */
class NotificationSupervisor(
                              emailService: EmailService,
                              wifiService: WifiService,
                              repo: NotificationRepo
                            )(implicit ec: ExecutionContext) extends Actor {

  /** Tracks active VisitNotificationActor instances by visitId. */
  private val active = mutable.Map.empty[Long, ActorRef]

  override def receive: Receive = {

    /**
     * Starts notification workflow if not already running for the given visit.
     *
     * Creates a VisitNotificationActor, stores reference, and triggers NotifyAll.
     */
    case StartNotifications(_, visitId, visitorName, visitorEmail, purpose, hostEmployeeId) =>
      if (!active.contains(visitId)) {

        val actor = context.actorOf(
          VisitNotificationActor.props(
            visitId,
            visitorName,
            visitorEmail,
            purpose,
            hostEmployeeId,
            emailService,
            wifiService,
            repo
          ),
          s"visit-$visitId"
        )

        // 🔥 FIX: actor must be tracked
        active.put(visitId, actor)

        actor ! VisitNotificationActor.NotifyAll
        context.system.log.info(s"[Supervisor] Started notifications for visit=$visitId")
      } else {
        context.system.log.info(s"[Supervisor] Notifications already active for visit=$visitId")
      }

    /**
     * Cancels notifications for a visit by stopping the child actor
     * and removing it from the active tracker.
     */
    case CancelNotifications(visitId) =>
      active.remove(visitId).foreach { ref =>
        ref ! VisitNotificationActor.Cancel
        context.system.log.info(s"[Supervisor] Cancelled notifications for visit=$visitId")
      }
  }
}
