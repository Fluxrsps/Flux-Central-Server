package dev.or2.central.exchange.pricing

import dev.or2.central.db.FlywayMigrator
import dev.or2.central.exchange.ExchangeConfig
import dev.or2.central.exchange.ExchangeEngine
import dev.or2.central.exchange.PricingConfig
import dev.or2.central.exchange.model.CreateOrderRequest
import dev.or2.central.exchange.model.CreateOrderResult
import dev.or2.central.exchange.model.Side
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarketPriceServiceTest {
    private lateinit var postgres: EmbeddedPostgres
    private lateinit var dataSource: DataSource
    private lateinit var engine: ExchangeEngine
    private lateinit var service: MarketPriceService
    private val names = AtomicInteger()
    private val config = ExchangeConfig(pricing = PricingConfig(minOrderAgeMinutes = 0))

    @BeforeAll
    fun start() {
        postgres = EmbeddedPostgres.builder().start()
        dataSource = postgres.postgresDatabase
        FlywayMigrator.migrate(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "")
        engine = ExchangeEngine(dataSource, { config })
        service = MarketPriceService(dataSource, { config })
    }

    @AfterAll
    fun stop() {
        postgres.close()
    }

    @Test
    fun firstRunSeedsFromBasePriceThenMovesWithTrades() {
        exec("INSERT INTO exchange_items (obj_id, base_price) VALUES (385, 800)")
        val first = assertNotNull(service.recompute(385))
        assertEquals(800, first.oldPrice)
        assertEquals("INITIAL_BASE_PRICE", first.reason)
        assertEquals(800, scalar("SELECT price FROM exchange_market_prices WHERE obj_id = 385"))

        repeat(30) {
            val seller = character()
            val buyer = character()
            place(seller, 385, Side.SELL, 10, 1000)
            place(buyer, 385, Side.BUY, 10, 1000)
        }
        val second = assertNotNull(service.recompute(385))
        assertEquals(30, second.tradesUsed)
        assertTrue(second.newPrice > 800, "price should move toward 1000, was ${second.newPrice}")
        assertEquals(2, scalar("SELECT count(*) FROM exchange_market_price_history WHERE obj_id = 385"))
        assertEquals(1, scalar("SELECT count(*) FROM exchange_events WHERE event_type = 'MARKET_PRICE_UPDATED' AND obj_id = 385"))
        val stable = scalar("SELECT stable_price FROM exchange_market_prices WHERE obj_id = 385")
        assertTrue(stable in 800..second.newPrice)
    }

    @Test
    fun itemsWithoutBasePriceOrTradesAreSkipped() {
        exec("INSERT INTO exchange_items (obj_id) VALUES (386)")
        val summary = service.recomputeAll()
        assertTrue(summary.skipped >= 1)
        assertEquals(0, scalar("SELECT count(*) FROM exchange_market_prices WHERE obj_id = 386"))
    }

    private fun place(character: Int, item: Int, side: Side, quantity: Long, limit: Long) {
        val created = engine.createOrder(CreateOrderRequest(character, item, side, quantity, limit, UUID.randomUUID().toString()))
        assertIs<CreateOrderResult.Pending>(created)
        engine.openOrder(created.order.id, character)
        engine.matchPass(created.order.id)
    }

    private fun character(): Int {
        val name = "pricing_${names.incrementAndGet()}"
        exec("INSERT INTO accounts (account_name, password_hash) VALUES ('$name', 'x')")
        exec("INSERT INTO account_characters (account_id, display_name) SELECT id, '$name' FROM accounts WHERE account_name = '$name'")
        return scalar("SELECT id FROM account_characters WHERE display_name = '$name'").toInt()
    }

    private fun exec(sql: String) {
        dataSource.connection.use { it.createStatement().use { st -> st.executeUpdate(sql) } }
    }

    private fun scalar(sql: String): Long =
        dataSource.connection.use { conn ->
            conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
        }
}
