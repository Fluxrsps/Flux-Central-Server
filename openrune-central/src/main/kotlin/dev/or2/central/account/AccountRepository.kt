package dev.or2.central.account

import dev.or2.sql.OpenRuneSql
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

class AccountRepository(
    private val dataSource: DataSource,
) {

    fun findByUsername(username: String): AccountRow? {
        val sql = OpenRuneSql.text("central/account/find_by_username.sql")

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, username)

                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null

                    AccountRow(
                        id = rs.getLong("id"),
                        username = rs.getString("username"),
                        passwordHash = rs.getString("password_hash"),
                        rights = rs.getString("rights") ?: "",
                    )
                }
            }
        }
    }

    fun insertIfAbsent(
        username: String,
        passwordHash: String,
        nowMillis: Long,
    ): Boolean {
        val sql = OpenRuneSql.text("central/account/insert_if_absent.sql")

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, username)
                ps.setString(2, passwordHash)
                ps.setTimestamp(3, nowMillis.toTimestamp())
                ps.setTimestamp(4, nowMillis.toTimestamp())

                ps.executeUpdate() > 0
            }
        }
    }
}

private fun Long.toTimestamp(): Timestamp = Timestamp.from(Instant.ofEpochMilli(this))