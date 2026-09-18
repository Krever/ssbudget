package ssbudget.backend.service

import cats.data.OptionT
import cats.syntax.all.*
import cats.effect.std.Supervisor
import cats.effect.{IO, Ref}
import org.http4s.HttpRoutes

import java.time.Instant
import scala.concurrent.duration.*

/** Drives [[BudgetSnapshotService.ensureUpToDate]] off ordinary traffic.
  *
  * There is nowhere better to put it. The machine suspends when nobody is using the app (`min_machines_running = 0`), so a midnight timer would fire
  * only on the days the app happened to be awake — which are exactly the days the snapshot would have been written anyway. Hanging it off requests
  * inverts that: every visit both records the day and repairs whatever the quiet days missed.
  *
  * Three properties make this safe to attach to every matched route:
  *   - it runs on the supervisor, so the response is never waiting on it;
  *   - it is throttled, so a page that fires a dozen API calls still does one pass;
  *   - it swallows its errors after logging them. A snapshot that cannot be written must not turn someone's page load into a 500.
  */
object BudgetSnapshotTrigger {

  /** How long to wait before another pass. Long enough that a page load does one, short enough that a session spanning midnight still records the new
    * day.
    */
  private val minInterval = 5.minutes

  def wrap(service: BudgetSnapshotService, supervisor: Supervisor[IO]): IO[HttpRoutes[IO] => HttpRoutes[IO]] =
    Ref.of[IO, Option[Instant]](None).map { lastRun => routes =>
      HttpRoutes[IO](request => routes(request) <* OptionT.liftF(maybeRun(service, supervisor, lastRun)))
    }

  /** Starts a pass unless one ran recently. The check and the stamp happen in a single `modify`, so concurrent requests cannot both win it. */
  private def maybeRun(service: BudgetSnapshotService, supervisor: Supervisor[IO], lastRun: Ref[IO, Option[Instant]]): IO[Unit] =
    for {
      now <- IO.realTimeInstant
      due <- lastRun.modify {
               case Some(previous) if previous.plusSeconds(minInterval.toSeconds).isAfter(now) => (Some(previous), false)
               case _                                                                          => (Some(now), true)
             }
      _   <- IO.whenA(due) {
               supervisor
                 .supervise(
                   service.ensureUpToDate.handleErrorWith(e => IO.println(s"Daily budget snapshot failed: ${e.getMessage}")),
                 )
                 .void
             }
    } yield ()
}
