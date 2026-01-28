package ssbudget.e2e

import org.scalatest.{BeforeAndAfterAll, Suites}

/** Master test suite that manages server lifecycle. Run with: sbt "e2e/testOnly ssbudget.e2e.E2ESuite"
  *
  * This suite:
  *   1. Starts backend on a random port (in-process)
  *   2. Starts frontend/vite on a random port (proxying to backend)
  *   3. Runs all E2E test specs
  *   4. Stops all servers
  */
class E2ESuite
    extends Suites(
      new DashboardSpec,
      new AccountsPageSpec,
      new BudgetPageSpec,
      new PeriodsPageSpec,
    )
    with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    // Only start servers if E2E_BASE_URL is not set (i.e., not using external servers)
    if sys.env.get("E2E_BASE_URL").isEmpty then {
      TestServers.startAll()
    }
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    if sys.env.get("E2E_BASE_URL").isEmpty then {
      TestServers.stopAll()
    }
  }
}
