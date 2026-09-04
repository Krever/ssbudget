package ssbudget.backend.analytics

import cats.effect.{IO, Resource}
import cats.effect.std.Supervisor
import org.http4s.HttpRoutes
import org.http4s.server.middleware.GZip
import sttp.capabilities.fs2.Fs2Streams
import sttp.client3.SttpBackend

import ssbudget.backend.auth.SessionService
import ssbudget.backend.db.repository.AnalyticsStateRepository

/** The analytics integration as one component: what the app's API answers about it, and the routes that serve Metabase.
  *
  * Analytics is optional, and this is where that optionality lives. When `SSBUDGET_METABASE_URL` is unset the bundle is inert — the service reports
  * `enabled = false` and the routes are empty — so nothing above has to thread an `Option` around or keep several of them in step.
  */
final case class Analytics(service: AnalyticsService, routes: HttpRoutes[IO])

object Analytics {

  def resource(
      dbPath: String,
      sttpBackend: SttpBackend[IO, Fs2Streams[IO]],
      state: AnalyticsStateRepository,
      supervisor: Supervisor[IO],
      sessionService: SessionService,
      testMode: Boolean,
  ): Resource[IO, Analytics] =
    for {
      configOpt <- Resource.eval(MetabaseConfig.fromEnv(dbPath))
      _         <- Resource.eval(IO.println(if configOpt.isDefined then "Metabase analytics: configured" else "Metabase analytics: not configured"))
      analytics <- configOpt match {
                     case None      => Resource.pure[IO, Analytics](Analytics(new AnalyticsService(None, state), HttpRoutes.empty[IO]))
                     case Some(cfg) =>
                       for {
                         client <- Resource.eval(MetabaseClient(cfg, sttpBackend))
                         // Provisioning waits on a Metabase that may still be booting, so it runs in the
                         // background: the app must not be held hostage to an optional extra coming up.
                         _      <- Resource.eval(supervisor.supervise(new MetabaseProvisioner(cfg, client, state).provision).void)
                       } yield Analytics(
                         new AnalyticsService(Some(cfg), state),
                         // The proxy asks Metabase for plain bytes (see AnalyticsProxyRoutes), so compressing on the
                         // way out is what keeps its multi-megabyte assets cheap over a real network.
                         GZip(AnalyticsProxyRoutes.make(cfg, client, sttpBackend, sessionService, testMode)),
                       )
                   }
    } yield analytics
}
