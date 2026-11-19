package ssbudget.backend

import org.http4s.implicits.http4sHeaderSyntax
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class StaticRoutesSpec extends AnyFreeSpec with Matchers {

  "the caching of a static file" - {
    "keeps a hashed asset for a year" in {
      val header = StaticRoutes.cachingFor("assets/index-12iTyvsq.js").value
      header should include("max-age=31536000")
      header should include("immutable")
    }

    "checks every other file on each use, so a new release shows at once" in {
      StaticRoutes.cachingFor("index.html").value shouldBe "no-cache"
      StaticRoutes.cachingFor("favicon.svg").value shouldBe "no-cache"
    }
  }
}
