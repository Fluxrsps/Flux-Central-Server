package dev.or2.central.server.logging

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.or2.central.CentralSchemaBootstrap
import dev.or2.central.logs.CentralActivityLog
import dev.or2.central.logs.CentralActivityLogJson
import dev.or2.central.logs.TradedItem
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CentralActivityLogRepositoryTest {
    companion object {
        private val nilLogUuid: UUID = UUID(0L, 0L)

        private lateinit var embedded: EmbeddedPostgres

        @JvmStatic
        @BeforeAll
        fun postgresUp() {
            embedded = EmbeddedPostgres.start()
        }

        @JvmStatic
        @AfterAll
        fun postgresDown() {
            if (::embedded.isInitialized) {
                embedded.close()
            }
        }
    }

    private lateinit var dataSource: DataSource
    private lateinit var repository: CentralActivityLogRepository
    private val json = CentralActivityLogJson.json

    @BeforeEach
    fun setup() {
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    dataSource = embedded.getPostgresDatabase()
                    maximumPoolSize = 4
                    connectionInitSql = "SET application_name = 'openrune_central_activity_log_test'"
                },
            )
        CentralSchemaBootstrap.apply(dataSource)
        repository = CentralActivityLogRepository(dataSource)
    }

    @AfterEach
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    private fun insertTestAccount(): Long =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO accounts (account_name, password_hash, rights) VALUES (?, ?, ?) RETURNING id",
            ).use { ps ->
                ps.setString(1, "actlog_test_" + System.nanoTime())
                ps.setString(2, "x")
                ps.setString(3, "")
                ps.executeQuery().use { rs ->
                    require(rs.next())
                    rs.getLong(1)
                }
            }
        }

    private fun insertCharacter(accountId: Long): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO account_characters (account_id, display_name) VALUES (?, ?) RETURNING id",
            ).use { ps ->
                ps.setLong(1, accountId)
                ps.setString(2, "login_count_char_" + System.nanoTime())
                ps.executeQuery().use { rs ->
                    require(rs.next())
                    rs.getInt(1)
                }
            }
        }

    @Test
    fun insertRoundTripChatTradeButton() {
        val testAccountId = insertTestAccount()
        val t0 = 1_700_000_000_000L
        val chat =
            CentralActivityLog.Chat(
                worldId = 1,
                occurredAtEpochMillis = t0,
                characterId = 0,
                accountId = testAccountId,
                message = "hello world",
            )
        val trade =
            CentralActivityLog.Trade(
                worldId = 1,
                occurredAtEpochMillis = t0 + 1,
                characterId = 10,
                accountId = testAccountId,
                initiatorCharacterId = 10,
                receiverCharacterId = 20,
                initiatorItems = listOf(TradedItem(id = "obj.abyssal_whip", quantity = 1)),
                receiverItems = listOf(TradedItem(id = "obj.coins", quantity = 25_000)),
            )
        val button =
            CentralActivityLog.ButtonClick(
                worldId = 1,
                occurredAtEpochMillis = t0 + 2,
                characterId = 0,
                accountId = testAccountId,
                interfaceId = "162",
                componentId = "42",
            )
        val chatUuid = repository.insert(chat)
        val tradeUuid = repository.insert(trade)
        val buttonUuid = repository.insert(button)
        assertTrue(chatUuid != nilLogUuid)
        assertTrue(tradeUuid != nilLogUuid)
        assertTrue(buttonUuid != nilLogUuid)

        assertEquals(
            chat,
            repository.findByLogUuid(chatUuid)?.toCentralActivityLog(),
        )
        assertEquals(
            trade,
            repository.findByLogUuid(tradeUuid)?.toCentralActivityLog(),
        )
        val tradeRow = repository.findByLogUuid(tradeUuid)!!
        val tradePivotCount =
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM activity_log_items WHERE activity_log_id = ?",
                ).use { ps ->
                    ps.setLong(1, tradeRow.id)
                    ps.executeQuery().use { rs ->
                        require(rs.next())
                        rs.getInt(1)
                    }
                }
            }
        assertEquals(2, tradePivotCount)

        val rows =
            repository.listByTypeAndTimeRange(
                logType = "chat",
                fromEpochMillisInclusive = t0 - 1,
                toEpochMillisExclusive = t0 + 10_000,
                limit = 10,
            )
        assertEquals(1, rows.size)
        assertEquals("chat", rows[0].logType)
        assertEquals(chatUuid, rows[0].logUuid)
        val decodedChat = rows[0].toCentralActivityLog()
        assertEquals(chat, decodedChat)

        val tradeRows =
            repository.listByTypeAndTimeRange("trade", t0, t0 + 10_000, 10)
        assertEquals(1, tradeRows.size)
        assertEquals(trade, tradeRows[0].toCentralActivityLog())

        val buttonRows =
            repository.listByTypeAndTimeRange("button_click", t0, t0 + 10_000, 10)
        assertEquals(1, buttonRows.size)
        assertEquals(buttonUuid, buttonRows[0].logUuid)
        assertEquals(button, buttonRows[0].toCentralActivityLog())
    }

    @Test
    fun countDistinctLoginAccountsForDayUsesLoginPayload() {
        val dayMillis = 1_720_000_000_000L
        val accountId1 =
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "INSERT INTO accounts (account_name, password_hash, rights) VALUES (?, ?, ?) RETURNING id",
                ).use { ps ->
                    ps.setString(1, "login_count_a_" + System.nanoTime())
                    ps.setString(2, "x")
                    ps.setString(3, "")
                    ps.executeQuery().use { rs ->
                        require(rs.next())
                        rs.getLong(1)
                    }
                }
            }
        val accountId2 =
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "INSERT INTO accounts (account_name, password_hash, rights) VALUES (?, ?, ?) RETURNING id",
                ).use { ps ->
                    ps.setString(1, "login_count_b_" + System.nanoTime())
                    ps.setString(2, "x")
                    ps.setString(3, "")
                    ps.executeQuery().use { rs ->
                        require(rs.next())
                        rs.getLong(1)
                    }
                }
            }
        val charId1 = insertCharacter(accountId1)
        val charId2 = insertCharacter(accountId2)
        val login1 =
            CentralActivityLog.Login(
                worldId = 1,
                occurredAtEpochMillis = dayMillis,
                characterId = charId1,
                accountId = accountId1,
            )
        val login1Again =
            CentralActivityLog.Login(
                worldId = 1,
                occurredAtEpochMillis = dayMillis + 60_000L,
                characterId = charId1,
                accountId = accountId1,
            )
        val login2 =
            CentralActivityLog.Login(
                worldId = 1,
                occurredAtEpochMillis = dayMillis + 120_000L,
                characterId = charId2,
                accountId = accountId2,
            )
        repository.insert(login1)
        repository.insert(login1Again)
        repository.insert(login2)
        val dayUtc =
            java.time.Instant.ofEpochMilli(dayMillis)
                .atZone(java.time.ZoneOffset.UTC)
                .toLocalDate()
                .toString()
        assertEquals(2, repository.countDistinctLoginAccountsForDay(dayUtc))
    }

    @Test
    fun listByCharacterUsesCharacterIdColumn() {
        val accountId =
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "INSERT INTO accounts (account_name, password_hash, rights) VALUES (?, ?, ?) RETURNING id",
                ).use { ps ->
                    ps.setString(1, "logtest_" + System.nanoTime())
                    ps.setString(2, "x")
                    ps.setString(3, "")
                    ps.executeQuery().use { rs ->
                        require(rs.next())
                        rs.getLong(1)
                    }
                }
            }
        val characterId =
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "INSERT INTO account_characters (account_id, display_name) VALUES (?, ?) RETURNING id",
                ).use { ps ->
                    ps.setLong(1, accountId)
                    ps.setString(2, "LogTest_" + System.nanoTime())
                    ps.executeQuery().use { rs ->
                        require(rs.next())
                        rs.getInt(1)
                    }
                }
            }
        val t = 1_710_000_000_000L
        val log =
            CentralActivityLog.PickupItem(
                worldId = 1,
                occurredAtEpochMillis = t,
                characterId = characterId,
                accountId = accountId,
                itemId = "526",
                quantity = 1,
                tileX = 3200,
                tileZ = 3200,
            )
        val insertedUuid = repository.insert(log)
        assertTrue(insertedUuid != nilLogUuid)
        val byOwner = repository.listByCharacter(characterId, limit = 5)
        assertEquals(1, byOwner.size)
        assertEquals(insertedUuid, byOwner[0].logUuid)
        assertEquals(characterId, byOwner[0].characterId)
        assertEquals("pickup_item", byOwner[0].logType)
        assertEquals(log, byOwner[0].toCentralActivityLog())
    }

    @Test
    fun logTypeMatchesSerialNames() {
        val samples: List<CentralActivityLog> =
            listOf(
                CentralActivityLog.Login(1, 1L, 1, 99L),
                CentralActivityLog.Logout(1, 1L, 1, 99L, 1L),
                CentralActivityLog.Chat(1, 1L, 0, 99L, "x"),
                CentralActivityLog.Trade(
                    1,
                    1L,
                    1,
                    99L,
                    initiatorCharacterId = 1,
                    receiverCharacterId = 2,
                    initiatorItems = emptyList(),
                    receiverItems = emptyList(),
                ),
                CentralActivityLog.ButtonClick(1, 1L, 0, 99L, "1", "2"),
                CentralActivityLog.PickupItem(1, 1L, 0, 99L, "obj.coins", 1),
                CentralActivityLog.DroppedItem(1, 1L, 0, 99L, "obj.logs", 1),
                CentralActivityLog.DestroyItem(1, 1L, 0, 99L, "obj.ashes", 1),
                CentralActivityLog.Command(1, 1L, 0, 99L, "tele", listOf("1", "2", "3")),
            )
        val encoded = samples.map { json.encodeToString(CentralActivityLog.serializer(), it) }
        assertTrue(encoded.all { it.contains("\"type\":") })
        assertEquals(
            listOf("login", "logout", "chat", "trade", "button_click", "pickup_item", "dropped_item", "destroy_item", "command"),
            samples.map { it.type },
        )
    }

    @Test
    fun listActivityLogIdsForItemOrdersNewestFirst() {
        val testAccountId = insertTestAccount()
        val t = 1_790_000_000_000L
        val itemId = "obj.pivot_track"
        val uEarly =
            repository.insert(
                CentralActivityLog.PickupItem(
                    worldId = 1,
                    occurredAtEpochMillis = t,
                    characterId = 0,
                    accountId = testAccountId,
                    itemId = itemId,
                    quantity = 1,
                ),
            )
        val uLate =
            repository.insert(
                CentralActivityLog.PickupItem(
                    worldId = 1,
                    occurredAtEpochMillis = t + 1000L,
                    characterId = 0,
                    accountId = testAccountId,
                    itemId = itemId,
                    quantity = 2,
                ),
            )
        val idEarly = repository.findByLogUuid(uEarly)!!.id
        val idLate = repository.findByLogUuid(uLate)!!.id
        assertEquals(
            listOf(idLate, idEarly),
            repository.listActivityLogIdsForItem(itemId, limit = 10),
        )
    }
}
