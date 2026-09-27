package dev.or2.central.exchange.sim

import dev.or2.central.db.FlywayMigrator
import dev.or2.central.exchange.ExchangeConfig
import dev.or2.central.exchange.ExchangeEngine
import dev.or2.central.exchange.health.AlertService
import dev.or2.central.exchange.health.ReconciliationService
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Throws random sequences of create, abandon, cancel, fill, claim and recover at the engine and
 * asserts the conservation invariants still hold, Part P14.
 *
 * The assertions are [ReconciliationService] itself, so this tests the production checks and the
 * production engine against each other: if either drifts, the run fails. Seeds are fixed, so a
 * failure is reproducible.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeFuzzTest {
    private lateinit var postgres: EmbeddedPostgres
    private lateinit var dataSource: DataSource
    private lateinit var engine: ExchangeEngine
    private lateinit var reconciliation: ReconciliationService
    // No grace period: it exists to stop login recovery racing a second creation step still in
    // flight, and nothing is in flight here. Without it every order the fuzz run abandons is too
    // young to recover and the run could never reach a settled state to check.
    private val config = ExchangeConfig(recoveryGraceSeconds = 0)

    @BeforeAll
    fun start() {
        postgres = EmbeddedPostgres.builder().start()
        dataSource = postgres.postgresDatabase
        FlywayMigrator.migrate(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "")
        engine = ExchangeEngine(dataSource, { config })
        reconciliation = ReconciliationService(dataSource, AlertService(dataSource))
    }

    @AfterAll
    fun stop() {
        postgres.close()
    }

    @Test
    fun randomSequencesNeverBreakConservation() {
        for (seed in listOf(1L, 7L, 42L)) {
            val simulation = ExchangeSimulation(dataSource, engine, traderCount = 12, itemCount = 5, seed = seed)
            val stats = simulation.run(steps = 400)
            simulation.recoverAll()

            val report = reconciliation.run()
            assertTrue(report.clean, "seed $seed broke conservation: ${report.mismatches.map { "${it.check} item=${it.objId} expected=${it.expected} actual=${it.actual}" }}")
            assertTrue(stats.fills.get() > 0, "seed $seed traded nothing, so it proved nothing")
            assertEquals(0, pendingOrders(), "seed $seed left orders PENDING after recovery")
        }
    }

    @Test
    fun abandonedOrdersAreRefundedOnlyWithTheSaveMarker() {
        val simulation = ExchangeSimulation(dataSource, engine, traderCount = 4, itemCount = 2, seed = 99)
        repeat(40) { simulation.abandonOrder(kotlin.random.Random(it.toLong())) }
        // Fewer than 40 survive: a PENDING order holds an order slot, so the slot limit refuses
        // the rest. That is the limit working, and it is why the count comes from the database.
        val stranded = pendingOrders()
        assertTrue(stranded > 0, "nothing was left stranded, so there is nothing to recover")

        simulation.recoverAll()
        assertEquals(0, pendingOrders(), "recovery must resolve every stranded order")
        assertTrue(reconciliation.run().clean)

        // Every abandoned order ends CANCELLED for SYSTEM_FAILURE, and only the ones whose save
        // carried the marker released anything into a collection box.
        val cancelled = scalar("SELECT count(*) FROM exchange_orders WHERE status = 'CANCELLED' AND cancel_reason = 'SYSTEM_FAILURE'")
        assertTrue(cancelled >= stranded, "expected at least $stranded system-failure cancellations, got $cancelled")
        val refunded = scalar("SELECT count(*) FROM exchange_events WHERE event_type = 'RECOVERY_ACTION' AND reason = 'pending order refunded'")
        val dropped = scalar("SELECT count(*) FROM exchange_events WHERE event_type = 'RECOVERY_ACTION' AND reason = 'pending order dropped'")
        assertTrue(refunded > 0 && dropped > 0, "expected both refunded and dropped recoveries, got $refunded/$dropped")
    }

    @Test
    fun theHarnessRefusesANonTestDatabase() {
        assertFalse(TestDatabaseGuard.isDisposable("jdbc:postgresql://db.fluxious-rsps.com:5432/openrune"))
        assertFalse(TestDatabaseGuard.isDisposable("jdbc:postgresql://127.0.0.1:5432/openrune_game"))
        assertTrue(TestDatabaseGuard.isDisposable("jdbc:postgresql://127.0.0.1:5432/openrune_test"))
        assertTrue(TestDatabaseGuard.isDisposable(postgres.getJdbcUrl("postgres", "postgres")))
    }

    private fun pendingOrders(): Long = scalar("SELECT count(*) FROM exchange_orders WHERE status = 'PENDING'")

    private fun scalar(sql: String): Long =
        dataSource.connection.use { conn -> conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } } }
}
