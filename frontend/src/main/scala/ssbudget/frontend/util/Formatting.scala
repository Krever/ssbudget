package ssbudget.frontend.util

import java.time.{Instant, LocalDate, ZoneId}
import java.time.format.DateTimeFormatter

object Formatting {

  private val dateFormatter      = DateTimeFormatter.ofPattern("MMM d, yyyy")
  private val shortDateFormatter = DateTimeFormatter.ofPattern("MMM d")
  private val dateTimeFormatter  = DateTimeFormatter.ofPattern("MMM d, HH:mm")
  // Use UTC instead of systemDefault() - systemDefault() fails silently in Scala.js
  // without the scala-java-time-tzdb dependency
  private val zone               = ZoneId.of("UTC")

  private val isoDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

  def formatIso(instant: Instant): String = {
    val localDate = instant.atZone(zone).toLocalDate
    isoDateFormatter.format(localDate)
  }

  def formatIsoToday: String = {
    isoDateFormatter.format(LocalDate.now(zone))
  }

  def formatMoneyShort(cents: Long): String = {
    val amount = cents / 100.0
    f"$amount%,.0f"
  }

  def formatDate(instant: Instant): String = {
    val localDate = instant.atZone(zone).toLocalDate
    dateFormatter.format(localDate)
  }

  def formatLocalDate(date: LocalDate): String = {
    dateFormatter.format(date)
  }

  def formatDateShort(instant: Instant): String = {
    val localDate = instant.atZone(zone).toLocalDate
    shortDateFormatter.format(localDate)
  }

  def formatDateTime(instant: Instant): String = {
    dateTimeFormatter.format(instant.atZone(zone))
  }

  /** CSS width for a progress bar, from a 0..1 fraction. */
  def progressWidth(fraction: Double): String = s"width: ${(fraction * 100).toInt}%"
}
