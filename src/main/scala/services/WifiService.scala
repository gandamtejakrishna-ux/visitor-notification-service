package services

import java.util.UUID
/**
 * Generates Wi-Fi credentials and sends them to visitors.
 */
class WifiService() {
  /**
   * Creates a random Wi-Fi username and password.
   */
  def createCredentials(): (String, String) = {
    val user = s"visitor_${UUID.randomUUID().toString.take(8)}"
    val pass = UUID.randomUUID().toString.take(10)
    (user, pass)
  }

  /**
   * Sends Wi-Fi credentials to the visitor by email.
   */
  def sendWifiCredentials(emailService: EmailService, to: String): Unit = {
    val (user, pass) = createCredentials()
    val subject = "Visitor Wi-Fi credentials"
    val body = s"Hello,\n\nYour visitor Wi-Fi credentials:\nUsername: $user\nPassword: $pass\n\nValid for this visit only."
    emailService.sendEmail(to, subject, body)
  }
}
