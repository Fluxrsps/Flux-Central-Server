package dev.or2.central.logs

import dev.or2.sql.OpenRuneSql
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import org.postgresql.util.PGobject

class CentralActivityLogRepository(
    private val dataSource: DataSource,
) {
    fun findByLogUuid(logUuid: UUID): CentralActivityLogRow? {
        val sql = OpenRuneSql.text("central/logs/select_by_log_uuid.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setObject(1, logUuid)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) null else rs.toCentralActivityLogRow()
                }
            }
        }
    }

    fun listByTypeAndTimeRange(
        logType: String,
        fromEpochMillisInclusive: Long,
        toEpochMillisExclusive: Long,
        limit: Int,
    ): List<CentralActivityLogRow> {
        val sql = OpenRuneSql.text("central/logs/select_by_type_range.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, logType)
                ps.setLong(2, fromEpochMillisInclusive)
                ps.setLong(3, toEpochMillisExclusive)
                ps.setInt(4, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.toCentralActivityLogRow())
                        }
                    }
                }
            }
        }
    }

    fun listByCharacter(
        characterId: Int,
        limit: Int,
    ): List<CentralActivityLogRow> {
        val sql = OpenRuneSql.text("central/logs/select_by_character.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, characterId)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.toCentralActivityLogRow())
                        }
                    }
                }
            }
        }
    }

    fun listActivityLogIdsForItem(
        itemId: String,
        limit: Int,
    ): List<Long> {
        val sql = OpenRuneSql.text("central/logs/select_activity_log_ids_by_item_id.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, itemId)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.getLong(1))
                        }
                    }
                }
            }
        }
    }

    /** [worldType] is the gameplay mode the row belongs to; one world may serve several. */
    fun insert(log: CentralActivityLog, worldType: String? = null): UUID {
        val sql = OpenRuneSql.text("central/logs/insert.sql")
        val payload =
            PGobject().apply {
                type = "jsonb"
                value = CentralActivityLogJson.json.encodeToString(CentralActivityLog.serializer(), log)
            }

        return dataSource.connection.use { conn ->
            val priorAutoCommit = conn.autoCommit
            try {
                conn.autoCommit = false
                val (logId, uuid) =
                    conn.prepareStatement(sql).use { ps ->
                        ps.setString(1, log.type)
                        ps.setLong(2, log.occurredAtEpochMillis)
                        ps.setLong(3, log.accountId)
                        ps.setInt(4, log.characterId)
                        ps.setInt(5, log.worldId)
                        ps.setString(6, worldType)
                        ps.setObject(7, payload)
                        ps.executeQuery().use { rs ->
                            require(rs.next()) { "INSERT ... RETURNING produced no row" }
                            rs.getLong("id") to
                                rs.getObject("log_uuid", UUID::class.java)
                                    ?: error("RETURNING log_uuid was null")
                        }
                    }
                insertActivityLogItems(conn, logId, (log as? ItemLineProducer)?.itemLines().orEmpty())
                conn.commit()
                uuid
            } catch (e: Exception) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                conn.autoCommit = priorAutoCommit
            }
        }
    }

    private fun insertActivityLogItems(
        conn: Connection,
        activityLogId: Long,
        lines: List<ActivityLogItemLine>,
    ) {
        if (lines.isEmpty()) return
        val sql = OpenRuneSql.text("central/logs/insert_activity_log_item.sql")
        conn.prepareStatement(sql).use { ps ->
            for (line in lines) {
                ps.setLong(1, activityLogId)
                ps.setString(2, line.type)
                ps.setInt(3, line.index)
                ps.setString(4, line.itemId)
                ps.setInt(5, line.quantity)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    fun countDistinctLoginAccountsForDay(dayUtc: String): Int {
        val day = LocalDate.parse(dayUtc)
        val startMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val endMillis = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val sql = OpenRuneSql.text("central/logs/count_distinct_login_accounts_day.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, startMillis)
                ps.setLong(2, endMillis)
                ps.executeQuery().use { rs ->
                    require(rs.next())
                    rs.getInt(1)
                }
            }
        }
    }

    private fun ResultSet.toCentralActivityLogRow(): CentralActivityLogRow =
        CentralActivityLogRow(
            id = getLong("id"),
            logUuid = getObject("log_uuid", UUID::class.java) ?: error("log_uuid was null"),
            logType = getString("log_type"),
            characterId = getInt("character_id"),
            payloadJson = getString("payload_json"),
        )
}

data class CentralActivityLogRow(
    val id: Long,
    val logUuid: UUID,
    val logType: String,
    val characterId: Int,
    val payloadJson: String,
) {
    fun toCentralActivityLog(): CentralActivityLog =
        CentralActivityLogJson.json.decodeFromString(CentralActivityLog.serializer(), payloadJson)
}
