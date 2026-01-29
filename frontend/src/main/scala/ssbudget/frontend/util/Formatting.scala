package ssbudget.frontend.util

import java.time.{Instant, LocalDate, ZoneId}
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object Formatting {

  private val dateFormatter      = DateTimeFormatter.ofPattern("MMM d, yyyy")
  private val shortDateFormatter = DateTimeFormatter.ofPattern("MMM d")
  // Use UTC instead of systemDefault() - systemDefault() fails silently in Scala.js
  // without the scala-java-time-tzdb dependency
  private val zone               = ZoneId.of("UTC")

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

  def daysRemaining(from: Instant, assumedPeriodDays: Int = 30): Int = {
    val daysSinceStart = ChronoUnit.DAYS.between(from, Instant.now()).toInt
    math.max(1, assumedPeriodDays - daysSinceStart)
  }

  def daysElapsed(from: Instant): Int = {
    ChronoUnit.DAYS.between(from, Instant.now()).toInt
  }

  def periodProgress(startDate: Instant, totalDays: Int = 30): Int = {
    val elapsed = daysElapsed(startDate)
    math.min(100, (elapsed * 100) / totalDays)
  }
}
