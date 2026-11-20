package ssbudget.backend.service

import cats.effect.IO

import java.time.{Instant, LocalDate, ZoneOffset}

/** What time it is, for the money logic: the period rules, the daily record, the category figures, the period reports.
  *
  * These rules depend on the date (which day of the period it is, which months are complete, whether a day is in the future). With the time read from
  * the system inside them, a test could only run them "today": its data had to be built around the current date, and a rule could not be checked on a
  * chosen day (the last day of a month, the day after a period ends). A service now gets this value, and a test gives it a fixed one.
  *
  * The services of the login and of the bank connection still read the system time themselves.
  */
final case class AppClock(now: IO[Instant]) {

  /** The calendar day (UTC) of [[now]]. */
  def today: IO[LocalDate] = now.map(LocalDate.ofInstant(_, ZoneOffset.UTC))
}

object AppClock {
  val system: AppClock = AppClock(IO.realTimeInstant)

  /** A clock that stands at `instant`: for tests. */
  def fixed(instant: Instant): AppClock = AppClock(IO.pure(instant))
}
