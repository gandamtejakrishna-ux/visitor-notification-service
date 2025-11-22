package models

case class VisitorJson(firstName: String, lastName: String, email: Option[String], phone: Option[String])

case class CheckinEvent(
                         eventType: String,
                         visitId: Long,
                         visitorId: Option[Long],
                         hostEmployeeId: Option[Long],
                         purpose: Option[String],
                         visitor: VisitorJson,
                         idProofHash: Option[String],
                         correlationId: Option[String],
                         checkinDate: Option[String]
                       )

case class CheckoutEvent(
                          eventType: String,
                          visitId: Long,
                          checkoutDate: Option[String],
                          correlationId: Option[String]
                        )
