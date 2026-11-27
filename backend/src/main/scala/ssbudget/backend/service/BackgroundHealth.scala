package ssbudget.backend.service

import cats.effect.IO
import org.slf4j.LoggerFactory
import ssbudget.shared.api.DataWarning

import java.util.concurrent.atomic.AtomicReference

/** The last result of the work that runs with no user who waits for it (the daily record of free money, and the history of the exchange rates that it
  * reads).
  *
  * Such work must not fail a page, so its errors are caught where it runs. Before, they went to the standard output only: if the daily record started
  * to fail, the chart stopped on some day and nothing said why. A task now runs through [[track]]: a failure is logged as an error and kept here
  * until the task succeeds again, and `DataHealth` gives it to the user as a warning.
  *
  * The state is of the process: it is one fact about the running server, and the tasks are started from more than one place.
  */
object BackgroundHealth {

  private val log      = LoggerFactory.getLogger("ssbudget.background")
  private val failures = new AtomicReference(Map.empty[Task, String])

  /** A background task, with the text that tells the user what its failure means. */
  enum Task(val failureMeans: String) {
    case DailyRecord   extends Task("The daily record of free money could not be brought up to date, so its chart can miss the newest days")
    case ExchangeRates extends Task("The history of the exchange rates could not be brought up to date, so some amounts are converted at older rates")
  }

  /** Runs the task. Its result is remembered; a failure is also logged, and then given back to the caller, who decides what to answer. */
  def track[A](task: Task)(work: IO[A]): IO[A] =
    work.attempt.flatMap {
      case Right(value) => IO(failures.updateAndGet(_ - task)).as(value)
      case Left(error)  =>
        IO {
          log.error(s"${task.failureMeans}", error)
          val _ = failures.updateAndGet(_ + (task -> Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))
        } *> IO.raiseError(error)
    }

  def warnings: IO[List[DataWarning]] =
    IO(
      failures.get.toList.sortBy(_._1.ordinal).map { case (task, message) => DataWarning("background", s"${task.failureMeans}. The error: $message") },
    )

  /** For tests: the state is of the process. */
  private[backend] def reset: IO[Unit] = IO(failures.set(Map.empty))
}
