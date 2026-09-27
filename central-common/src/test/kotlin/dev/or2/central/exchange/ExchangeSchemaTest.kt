package dev.or2.central.exchange

import dev.or2.central.db.FlywayMigrator
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the real migrations against an embedded PostgreSQL and checks that the exchange schema
 * refuses the states the spec forbids: broken reservations, moving terminal orders, rewriting
 * the ledger and recording a fill twice.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeSchemaTest {
    private lateinit var postgres: EmbeddedPostgres
    private var characterId = 0
    private var counterpartyId = 0

    @BeforeAll
    fun start() {
        postgres = EmbeddedPostgres.builder().start()
        FlywayMigrator.migrate(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "")
        connection().use { conn ->
            characterId = conn.newCharacter("exchange_a")
            counterpartyId = conn.newCharacter("exchange_b")
            conn.exec("INSERT INTO exchange_items (obj_id) VALUES (385), (379)")
        }
    }

    @AfterAll
    fun stop() {
        postgres.close()
    }

    @Test
    fun allExchangeTablesExist() {
        val expected =
            setOf(
                "exchange_items", "exchange_orders", "exchange_trades", "exchange_collection_items",
                "exchange_collection_gp", "exchange_claims", "exchange_buy_limit_usage",
                "exchange_events", "exchange_config", "exchange_config_history", "exchange_freezes",
                "exchange_stats_hourly", "exchange_stats_hourly_traders", "exchange_stats_daily",
                "exchange_market_prices", "exchange_market_price_history", "exchange_flags",
                "exchange_alerts", "exchange_system_liquidity", "exchange_system_usage",
                "exchange_notifications", "exchange_reversals", "exchange_debts",
                "exchange_economy_snapshots", "exchange_account_state",
            )
        connection().use { conn ->
            val actual = mutableSetOf<String>()
            conn.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_name LIKE 'exchange_%'",
            ).use { ps -> ps.executeQuery().use { rs -> while (rs.next()) actual += rs.getString(1) } }
            assertEquals(expected, actual)
        }
    }

    @Test
    fun reservationMustMatchRemainingQuantity() {
        connection().use { conn ->
            val id = conn.newOrder(side = "BUY", quantity = 900, limit = 500)
            conn.exec("UPDATE exchange_orders SET status = 'OPEN', reserved_amount = 450000, opened_at = now() WHERE id = $id")
            conn.assertRefused(CHECK_VIOLATION) {
                conn.exec("UPDATE exchange_orders SET filled_quantity = 600, status = 'PARTIALLY_FILLED' WHERE id = $id")
            }
            conn.exec(
                "UPDATE exchange_orders SET filled_quantity = 600, status = 'PARTIALLY_FILLED', reserved_amount = 150000 WHERE id = $id",
            )
            assertEquals(300L, conn.scalar("SELECT remaining_quantity FROM exchange_orders WHERE id = $id"))
        }
    }

    @Test
    fun terminalOrdersNeverChange() {
        connection().use { conn ->
            val id = conn.newOrder(side = "SELL", quantity = 10, limit = 100)
            conn.exec("UPDATE exchange_orders SET status = 'OPEN', reserved_amount = 10, opened_at = now() WHERE id = $id")
            conn.exec(
                "UPDATE exchange_orders SET status = 'CANCELLED', cancel_reason = 'PLAYER', reserved_amount = 0, cancelled_at = now() WHERE id = $id",
            )
            conn.assertRefused(CHECK_VIOLATION) {
                conn.exec("UPDATE exchange_orders SET status = 'OPEN', cancel_reason = NULL, reserved_amount = 10 WHERE id = $id")
            }
            conn.assertRefused(CHECK_VIOLATION) { conn.exec("UPDATE exchange_orders SET world = 2 WHERE id = $id") }
            conn.assertRefused(CHECK_VIOLATION) { conn.exec("DELETE FROM exchange_orders WHERE id = $id") }
        }
    }

    @Test
    fun onlySpecTransitionsAreAllowed() {
        connection().use { conn ->
            val id = conn.newOrder(side = "SELL", quantity = 10, limit = 100)
            conn.assertRefused(CHECK_VIOLATION) {
                conn.exec("UPDATE exchange_orders SET status = 'FILLED', filled_quantity = 10, completed_at = now() WHERE id = $id")
            }
            conn.assertRefused(CHECK_VIOLATION) {
                conn.exec("UPDATE exchange_orders SET status = 'CANCELLED', cancel_reason = 'PLAYER', cancelled_at = now() WHERE id = $id")
            }
            conn.exec(
                "UPDATE exchange_orders SET status = 'CANCELLED', cancel_reason = 'SYSTEM_FAILURE', cancelled_at = now() WHERE id = $id",
            )
        }
    }

    @Test
    fun ledgerIsAppendOnly() {
        connection().use { conn ->
            conn.exec(
                "INSERT INTO exchange_events (event_type, character_id, correlation_id) VALUES ('ORDER_CREATED', $characterId, '${UUID.randomUUID()}')",
            )
            val id = conn.scalar("SELECT max(id) FROM exchange_events")
            conn.assertRefused(CHECK_VIOLATION) { conn.exec("UPDATE exchange_events SET amount = 1 WHERE id = $id") }
            conn.assertRefused(CHECK_VIOLATION) { conn.exec("DELETE FROM exchange_events WHERE id = $id") }
            conn.assertRefused(CHECK_VIOLATION) {
                conn.exec(
                    "INSERT INTO exchange_events (event_type, staff_character_id, correlation_id) VALUES ('ADMIN_ACTION', $characterId, '${UUID.randomUUID()}')",
                )
            }
        }
    }

    @Test
    fun sameFillCannotBeRecordedTwice() {
        connection().use { conn ->
            val buy = conn.newOrder(side = "BUY", quantity = 100, limit = 200, character = characterId)
            val sell = conn.newOrder(side = "SELL", quantity = 100, limit = 100, character = counterpartyId)
            val insert = { conn.insertTrade(buy, sell, filledBefore = 0) }
            insert()
            conn.assertRefused(UNIQUE_VIOLATION) { insert() }
            val tradeId = conn.scalar("SELECT max(id) FROM exchange_trades")
            conn.assertRefused(CHECK_VIOLATION) { conn.exec("DELETE FROM exchange_trades WHERE id = $tradeId") }
            conn.assertRefused(CHECK_VIOLATION) { conn.exec("UPDATE exchange_trades SET tax = 0 WHERE id = $tradeId") }
            conn.exec("UPDATE exchange_trades SET reversed_at = now() WHERE id = $tradeId")
            conn.assertRefused(CHECK_VIOLATION) { conn.exec("UPDATE exchange_trades SET reversed_at = now() WHERE id = $tradeId") }
        }
    }

    @Test
    fun seededTradesCarryNoPartiesAndCanBePurged() {
        connection().use { conn ->
            conn.assertRefused(CHECK_VIOLATION) {
                conn.exec(
                    """
                    INSERT INTO exchange_trades (buyer_character_id, maker_side, obj_id, quantity, unit_price, gross_value,
                        tax_rate_bps, tax, net_value, source, correlation_id)
                    VALUES ($characterId, 'SELL', 385, 5, 100, 500, 0, 0, 500, 'SEEDED', '${UUID.randomUUID()}')
                    """.trimIndent(),
                )
            }
            conn.exec(
                """
                INSERT INTO exchange_trades (maker_side, obj_id, quantity, unit_price, gross_value,
                    tax_rate_bps, tax, net_value, source, correlation_id)
                VALUES ('SELL', 385, 5, 100, 500, 0, 0, 500, 'SEEDED', '${UUID.randomUUID()}')
                """.trimIndent(),
            )
            assertEquals(1, conn.exec("DELETE FROM exchange_trades WHERE source = 'SEEDED'"))
        }
    }

    @Test
    fun oneActiveFreezePerTarget() {
        connection().use { conn ->
            conn.exec("INSERT INTO exchange_freezes (scope, reason_code, reason) VALUES ('GLOBAL', 'UPDATE', 'test')")
            conn.assertRefused(UNIQUE_VIOLATION) {
                conn.exec("INSERT INTO exchange_freezes (scope, reason_code, reason) VALUES ('GLOBAL', 'UPDATE', 'again')")
            }
            conn.assertRefused(CHECK_VIOLATION) {
                conn.exec("INSERT INTO exchange_freezes (scope, target_id, reason_code, reason) VALUES ('GLOBAL', 385, 'X', 'x')")
            }
            conn.exec("UPDATE exchange_freezes SET lifted_at = now() WHERE scope = 'GLOBAL'")
            conn.exec("INSERT INTO exchange_freezes (scope, reason_code, reason) VALUES ('GLOBAL', 'UPDATE', 'after lift')")
        }
    }

    @Test
    fun hourlyBucketVolumeMustSplitBySource() {
        connection().use { conn ->
            conn.assertRefused(CHECK_VIOLATION) {
                conn.exec(
                    """
                    INSERT INTO exchange_stats_hourly (obj_id, bucket_start, open_price, close_price, low_price, high_price,
                        vwap_numerator, vwap_denominator, volume, transaction_count, player_volume)
                    VALUES (385, date_trunc('hour', now()), 100, 100, 100, 100, 1000, 10, 10, 1, 5)
                    """.trimIndent(),
                )
            }
            conn.exec(
                """
                INSERT INTO exchange_stats_hourly (obj_id, bucket_start, open_price, close_price, low_price, high_price,
                    vwap_numerator, vwap_denominator, volume, transaction_count, player_volume, system_volume)
                VALUES (385, date_trunc('hour', now()), 100, 100, 100, 100, 1000, 10, 10, 1, 5, 5)
                """.trimIndent(),
            )
        }
    }

    private fun connection(): Connection = postgres.postgresDatabase.connection

    private fun Connection.newCharacter(name: String): Int {
        exec("INSERT INTO accounts (account_name, password_hash) VALUES ('$name', 'x')")
        val accountId = scalar("SELECT id FROM accounts WHERE account_name = '$name'")
        exec("INSERT INTO account_characters (account_id, display_name) VALUES ($accountId, '$name')")
        return scalar("SELECT id FROM account_characters WHERE display_name = '$name'").toInt()
    }

    private fun Connection.newOrder(side: String, quantity: Long, limit: Long, character: Int = characterId): Long {
        val request = UUID.randomUUID().toString()
        exec(
            """
            INSERT INTO exchange_orders (character_id, obj_id, side, quantity, limit_price, client_request_id, correlation_id)
            VALUES ($character, 385, '$side', $quantity, $limit, '$request', '${UUID.randomUUID()}')
            """.trimIndent(),
        )
        return scalar("SELECT id FROM exchange_orders WHERE client_request_id = '$request'")
    }

    private fun Connection.insertTrade(buy: Long, sell: Long, filledBefore: Long) {
        exec(
            """
            INSERT INTO exchange_trades (buy_order_id, sell_order_id, buyer_character_id, seller_character_id, maker_side,
                obj_id, quantity, unit_price, gross_value, tax_rate_bps, tax, net_value, source, buy_filled_before, correlation_id)
            VALUES ($buy, $sell, $characterId, $counterpartyId, 'SELL', 385, 100, 100, 10000, 200, 200, 9800, 'PLAYER',
                $filledBefore, '${UUID.randomUUID()}')
            """.trimIndent(),
        )
    }

    private fun Connection.exec(sql: String): Int = createStatement().use { it.executeUpdate(sql) }

    private fun Connection.scalar(sql: String): Long =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                assertTrue(rs.next(), "no row for: $sql")
                rs.getLong(1)
            }
        }

    private fun Connection.assertRefused(sqlState: String, block: () -> Unit) {
        try {
            block()
        } catch (e: SQLException) {
            assertEquals(sqlState, e.sqlState, "wrong SQLSTATE: ${e.message}")
            return
        }
        fail("statement was accepted but should have been refused with SQLSTATE $sqlState")
    }

    private companion object {
        const val CHECK_VIOLATION = "23514"
        const val UNIQUE_VIOLATION = "23505"
    }
}
