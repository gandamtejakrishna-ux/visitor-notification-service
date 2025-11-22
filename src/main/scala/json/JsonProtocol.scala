package json

import models._
import models.VisitorJson
import play.api.libs.json.{Json, Reads}
/**
 * Provides implicit JSON deserializers (Reads) for event payloads.
 * Enables automatic conversion from incoming Kafka/HTTP JSON into Scala models.
 */
object JsonProtocol {
  /** JSON -> VisitorJson converter. Used when parsing visitor details. */
  implicit val visitorJsonReads: Reads[VisitorJson] = Json.reads[VisitorJson]

  /** JSON -> CheckinEvent converter. Parses visitor.checkin event payload. */
  implicit val checkinReads: Reads[CheckinEvent] = Json.reads[CheckinEvent]

  /** JSON -> CheckoutEvent converter. Parses visitor.checkout event payload. */
  implicit val checkoutReads: Reads[CheckoutEvent] = Json.reads[CheckoutEvent]
}
