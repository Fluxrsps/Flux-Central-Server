package dev.or2.central.exchange.sim

import dev.or2.central.db.FlywayMigrator
import dev.or2.central.exchange.ExchangeConfig
import dev.or2.central.exchange.ExchangeEngine
import dev.or2.central.exchange.ExchangeMetrics
import dev.or2.central.exchange.health.AlertService
import dev.or2.central.exchange.health.ReconciliationService
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Many simulated traders hitting the engine at once, Part P14. Proves that concurrency does not
 * break conservation and gives a latency figure to compare against after a change.
 *
 * Runs a modest size by default so it stays a normal test; the spec's 100 / 500 / 1000 player
 * sweeps are the same code with bigger numbers:
 *
 * ```
 * gradlew :central-common:test --tests "*ExchangeLoadTest*" -Dexchange.load.traders=500 -Dexchange.load.threads=32
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeLoadTest {
    private lateinit var postgres: EmbeddedPostgres
    private lateinit var dataSource: DataSource
    private lateinit var engine: ExchangeEngine
    private lateinit var reconciliation: ReconciliationService

    private val traders = System.getProperty("exchange.load.traders")?.toIntOrNull() ?: 100
    private val threads = System.getProperty("exchange.load.threads")?.toIntOrNull() ?: 8
    private val stepsPerThread = System.getProperty("exchange.load.steps")?.toIntOrNull() ?: 60

    @BeforeAll
    fun start() {
        postgres = EmbeddedPostgres.builder().setPort(0).start()
        dataSource = postgres.postgresDatabase
        FlywayMigrator.migrate(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "")
        engine = ExchangeEngine(dataSource, { ExchangeConfig() })
        reconciliation = ReconciliationService(dataSource, AlertService(dataSource))
    }

    @AfterAll
    fun stop() {
        postgres.close()
    }

    @Test
    fun concurrentTradersKeepTheLedgerBalanced() {
        val simulation = ExchangeSimulation(dataSource, engine, traderCount = traders, itemCount = 20, seed = 5)
        simulation.traders
        simulation.items

        val pool = Executors.newFixedThreadPool(threads)
        val before = ExchangeMetrics.snapshot()
        val startedAt = System.nanoTime()
        try {
            val futures =
                (1..threads).map { worker ->
                    pool.submit {
                        val rng = Random(worker.toLong() * 31)
                        repeat(stepsPerThread) { simulation.step(rng) }
                    }
                }
            for (future in futures) future.get(5, TimeUnit.MINUTES)
        } finally {
            pool.shutdown()
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        simulation.recoverAll()

        val after = ExchangeMetrics.snapshot()
        val latency = after.latency
        println(
            "load: traders=$traders threads=$threads steps=${threads * stepsPerThread} in ${elapsedMs}ms " +
                "| ${simulation.stats} | retries=${after.retries - before.retries} deadlocks=${after.deadlocks - before.deadlocks} " +
                "| match p50=${latency.p50}ms p95=${latency.p95}ms p99=${latency.p99}ms max=${latency.max}ms",
        )

        val report = reconciliation.run()
        assertTrue(
            report.clean,
            "concurrent load broke conservation: ${report.mismatches.map { "${it.check} item=${it.objId} expected=${it.expected} actual=${it.actual}" }}",
        )
        assertTrue(simulation.stats.fills.get() > 0, "load test traded nothing")
        assertTrue(after.deadlocks == before.deadlocks, "skip-locked scans should never deadlock, saw ${after.deadlocks - before.deadlocks}")
    }
}
