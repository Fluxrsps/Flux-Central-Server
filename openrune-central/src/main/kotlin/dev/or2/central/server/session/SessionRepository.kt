package dev.or2.central.server.session

import dev.or2.sql.OpenRuneSql
import java.sql.ResultSet
import javax.sql.DataSource

class WorldSessionRepository(
    private val dataSource: DataSource,
) {

    fun findByTokenHash(tokenHash: ByteArray): SessionRow? {
        val sql = OpenRuneSql.text("central/session/find_by_token_hash.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setBytes(1, tokenHash)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    readRow(rs)
                }
            }
        }
    }

    fun touchById(sessionId: Long, nowMillis: Long) {
        val sql = OpenRuneSql.text("central/session/touch_by_id.sql")
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, nowMillis)
                ps.setLong(2, sessionId)
                ps.executeUpdate()
            }
        }
    }

    fun deleteById(sessionId: Long) {
        val sql = OpenRuneSql.text("central/session/delete_by_id.sql")
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, sessionId)
                ps.executeUpdate()
            }
        }
    }

    fun deleteStale(beforeMillis: Long): Int {
        val sql = OpenRuneSql.text("central/session/delete_stale.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, beforeMillis)
                ps.executeUpdate()
            }
        }
    }

    fun deleteStaleReturning(beforeMillis: Long): List<SessionRow> {
        val sql =
            """
        DELETE FROM sessions
        WHERE last_seen_at < ?
        RETURNING id, account_id, world_id, character_id, token_hash, created_at, last_seen_at
        """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, beforeMillis)

                ps.executeQuery().use { rs ->
                    val out = mutableListOf<SessionRow>()

                    while (rs.next()) {
                        out += readRow(rs)
                    }

                    out
                }
            }
        }
    }

    fun findDistinctWorldIdsByAccount(accountId: Long): List<Int> {
        val sql = OpenRuneSql.text("central/session/select_distinct_world_ids_by_account.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, accountId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.getInt("world_id"))
                        }
                    }
                }
            }
        }
    }

    fun deleteAllForAccount(accountId: Long): Int {
        val sql = OpenRuneSql.text("central/session/delete_by_account_id.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, accountId)
                ps.executeUpdate()
            }
        }
    }

    fun totalOnline(): Int {
        val sql = OpenRuneSql.text("central/session/count_all.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.executeQuery().use { rs ->
                    require(rs.next())
                    rs.getInt(1)
                }
            }
        }
    }

    fun countsByWorld(): Map<Int, Int> {
        val sql = OpenRuneSql.text("central/session/counts_by_world.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) {
                            put(rs.getInt("world_id"), rs.getInt("c"))
                        }
                    }
                }
            }
        }
    }

    private fun readRow(rs: ResultSet): SessionRow =
        SessionRow(
            id = rs.getLong("id"),
            accountId = rs.getLong("account_id"),
            worldId = rs.getInt("world_id"),
            characterId = rs.getObject("character_id")?.let { (it as Number).toInt() },
            tokenHash = rs.getBytes("token_hash"),
            createdAt = rs.getLong("created_at"),
            lastSeenAt = rs.getLong("last_seen_at"),
        )
}