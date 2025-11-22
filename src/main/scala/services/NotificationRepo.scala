package services

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import scala.concurrent.{ExecutionContext, Future}
import java.sql.ResultSet

/**
 * Repository for the notification service.
 *
 * Manages notification state (PENDING, SENT, FAILED, CANCELLED)
 * in the `notifications` table using raw JDBC + HikariCP.
 *
 * Used by VisitNotificationActor to track delivery status
 * of emails, Wi-Fi messages, and security alerts.
 */
class NotificationRepo(jdbcUrl: String, user: String, pass: String)(implicit ec: ExecutionContext) {
  private val cfg = new HikariConfig()
  cfg.setJdbcUrl(jdbcUrl)
  cfg.setUsername(user)
  cfg.setPassword(pass)
  cfg.setMaximumPoolSize(1)
  private val ds = new HikariDataSource(cfg)

  /** Closes the underlying connection pool. */
  def close(): Unit = ds.close()

  /**
   * Inserts a PENDING notification entry for a visit/role.
   * If the row already exists, no-op due to ON DUPLICATE KEY.
   *
   * @param visitId visit identifier
   * @param role notification target role (HOST / IT / SECURITY)
   * @param channel email/sms/etc
   */
  def upsertPending(visitId: Long, role: String, channel: String): Future[Unit] = Future {
    val conn = ds.getConnection
    try {
      val sql =
        """
          |INSERT INTO notifications (visit_id, role, channel, status, attempts)
          |VALUES (?, ?, ?, 'PENDING', 0)
          |ON DUPLICATE KEY UPDATE visit_id = visit_id
        """.stripMargin
      val ps = conn.prepareStatement(sql)
      ps.setLong(1, visitId)
      ps.setString(2, role)
      ps.setString(3, channel)
      ps.executeUpdate()
      ps.close()
    } finally conn.close()
  }

  /**
   * Checks whether a notification is already marked as SENT.
   *
   * @param visitId visit identifier
   * @param role notification role
   * @return true if already SENT, else false
   */
  def isSent(visitId: Long, role: String): Future[Boolean] = Future {
    val conn = ds.getConnection
    try {
      val ps = conn.prepareStatement("SELECT status FROM notifications WHERE visit_id=? AND role=?")
      ps.setLong(1, visitId); ps.setString(2, role)
      val rs = ps.executeQuery()
      val r = if (rs.next()) rs.getString("status") == "SENT" else false
      rs.close(); ps.close(); r
    } finally conn.close()
  }

  /**
   * Marks a notification as SENT.
   *
   * @param visitId visit identifier
   * @param role target role
   * @param providerRef optional provider message id (email id / sms id)
   */
  def markSent(visitId: Long, role: String, providerRef: Option[String]): Future[Unit] = Future {
    val conn = ds.getConnection
    try {
      val ps = conn.prepareStatement("UPDATE notifications SET status='SENT', attempts = attempts + 1, provider_ref=?, created_at = CURRENT_TIMESTAMP WHERE visit_id=? AND role=?")
      ps.setString(1, providerRef.orNull); ps.setLong(2, visitId); ps.setString(3, role)
      ps.executeUpdate(); ps.close()
    } finally conn.close()
  }

  /**
   * Marks a notification as FAILED and stores a truncated error message.
   *
   * @param visitId visit identifier
   * @param role notification role
   * @param err failure message
   */
  def markFailed(visitId: Long, role: String, err: String): Future[Unit] = Future {
    val conn = ds.getConnection
    try {
      val sql =
        """
          |UPDATE notifications
          |SET status = 'FAILED',
          |    attempts = attempts + 1,
          |    provider_ref = ?
          |WHERE visit_id = ? AND role = ?
      """.stripMargin

      val ps = conn.prepareStatement(sql)

      // trim to provider_ref column size limit.
      val truncated = if (err.length > 150) err.take(147) + "..." else err
      ps.setString(1, truncated)
      ps.setLong(2, visitId)
      ps.setString(3, role)

      ps.executeUpdate()
      ps.close()
    } finally conn.close()
  }

  /**
   * Marks a notification as CANCELLED after a visitor checks out.
   *
   * @param visitId visit identifier
   * @param role notification role
   */
  def markCancelled(visitId: Long, role: String): Future[Unit] = Future {
    val conn = ds.getConnection
    try {
      val ps = conn.prepareStatement("UPDATE notifications SET status='CANCELLED' WHERE visit_id=? AND role=?")
      ps.setLong(1, visitId); ps.setString(2, role)
      ps.executeUpdate(); ps.close()
    } finally conn.close()
  }
}
