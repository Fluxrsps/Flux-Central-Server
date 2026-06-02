package dev.or2.central.account.punishment

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.or2.central.CentralSchemaBootstrap
import dev.or2.central.account.PunishmentService
import dev.or2.central.logs.CentralActivityLog
import dev.or2.central.server.logging.CentralActivityLogRepository
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PunishmentRepositoryCentralActivityLogsTest {
    companion object {
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
    private lateinit var punishmentService: PunishmentService
    private lateinit var activityLogRepository: CentralActivityLogRepository

    @BeforeEach
    fun setup() {
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    dataSource = embedded.getPostgresDatabase()
                    maximumPoolSize = 4
                    connectionInitSql = "SET application_name = 'openrune_punishment_logs_test'"
                },
            )
        CentralSchemaBootstrap.apply(dataSource)
        punishmentService = PunishmentService(dataSource)
        activityLogRepository = CentralActivityLogRepository(dataSource)
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "TRUNCATE activity_log_attachments, activity_log_items, punishments, activity_logs, accounts RESTART IDENTITY CASCADE",
                )
            }
        }
    }

    @AfterEach
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    @Test
    fun attachMultipleCentralActivityLogUuids() {
        val accountId =
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "INSERT INTO accounts (account_name, password_hash, rights) VALUES ('p', 'x', '') RETURNING id",
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        require(rs.next())
                        rs.getLong(1)
                    }
                }
            }
        val punishmentId =
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO punishments (
                        scope, account_id, character_id, kind, issued_at, expires_at,
                        reason, private_notes, public_notes, issued_by, approved_by, status, repo_link_uuid
                    )
                    VALUES ('account', ?, NULL, 'mute', CURRENT_TIMESTAMP, NULL,
                        'r', NULL, NULL, 's', NULL, 'active', NULL)
                    RETURNING id
                    """.trimIndent(),
                ).use { ps ->
                    ps.setLong(1, accountId)
                    ps.executeQuery().use { rs ->
                        require(rs.next())
                        rs.getLong(1)
                    }
                }
            }
        val t = 1_800_000_000_000L
        val u1 =
            activityLogRepository.insert(
                CentralActivityLog.Chat(
                    worldId = 1,
                    occurredAtEpochMillis = t,
                    characterId = 0,
                    accountId = accountId,
                    message = "a",
                ),
            )
        val u2 =
            activityLogRepository.insert(
                CentralActivityLog.Chat(
                    worldId = 1,
                    occurredAtEpochMillis = t + 1,
                    characterId = 0,
                    accountId = accountId,
                    message = "b",
                ),
            )
        assertEquals(2, punishmentService.attachCentralActivityLogs(punishmentId, listOf(u1, u2)))
        val attached = punishmentService.listAttachedCentralActivityLogUuids(punishmentId).toSet()
        assertEquals(setOf(u1, u2), attached)
        assertEquals(0, punishmentService.attachCentralActivityLogs(punishmentId, listOf(u1)))
        assertEquals(0, punishmentService.attachCentralActivityLogs(punishmentId, listOf(UUID.randomUUID())))
        assertTrue(punishmentService.detachCentralActivityLog(punishmentId, u1))
        assertEquals(setOf(u2), punishmentService.listAttachedCentralActivityLogUuids(punishmentId).toSet())
        assertFalse(punishmentService.detachCentralActivityLog(punishmentId, u1))
    }
}
