package ssbudget.backend.auth

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** Slows down guesses at the password. The first failures cost nothing, so a typing error is free. After `freeFailures` failures in a row each
  * further failure locks the login for a time that doubles, up to `maxLockSeconds`. A correct password clears the count.
  *
  * The state is in memory: a restart clears it, which is acceptable for a limit that only has to make guessing slow.
  */
final class LoginThrottle(freeFailures: Int = 5, firstLockSeconds: Long = 30, maxLockSeconds: Long = 900) {
  import LoginThrottle.State

  private val state = new AtomicReference(State(0, None))

  /** The seconds still to wait, when the login is locked at `now`. */
  def lockedFor(now: Instant): Option[Long] =
    state.get.lockedUntil.filter(_.isAfter(now)).map(until => math.max(1L, java.time.Duration.between(now, until).getSeconds))

  def failed(now: Instant): Unit = {
    state.updateAndGet { s =>
      val failures = s.failures + 1
      val over     = failures - freeFailures
      val lock     = Option.when(over > 0)(now.plusSeconds(math.min(maxLockSeconds, firstLockSeconds << math.min(over - 1, 10))))
      State(failures, lock)
    }
    ()
  }

  def succeeded(): Unit = state.set(State(0, None))
}

object LoginThrottle {
  final private case class State(failures: Int, lockedUntil: Option[Instant])
}
