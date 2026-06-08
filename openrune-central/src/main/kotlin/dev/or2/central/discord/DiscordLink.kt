package dev.or2.central.discord

import dev.or2.central.util.config.DiscordRuntimeConfig
import dev.or2.sql.OpenRuneSql
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlin.random.Random
import org.slf4j.LoggerFactory

class DiscordLinkService(
    private val dataSource: DataSource,
    private val guildMembers: DiscordGuildMembers,
    private val config: DiscordRuntimeConfig,
) {
    private val log = LoggerFactory.getLogger(DiscordLinkService::class.java)
    private val verificationMessages = ConcurrentHashMap<Long, VerificationMessageRef>()

    fun resolveDiscordUserId(username: String): Long? = guildMembers.findUserIdByUsername(username)

    fun createGamePending(
        accountId: Int,
        discordUserId: Long,
    ): Int {
        deletePendingByAccountId(accountId)
        deletePendingByDiscordUserId(discordUserId)
        val code = Random.nextInt(1000, 10_000)
        val expiresAt = Instant.now().plusSeconds(config.pendingTtlMinutes * 60)
        insertPending(accountId, discordUserId, code, expiresAt)
        return code
    }

    fun invalidatePending(accountId: Int) {
        val pending = findPendingByAccountId(accountId)
        deletePendingByAccountId(accountId)
        if (pending != null) {
            verificationMessages.remove(pending.discordUserId)
        }
    }

    fun findPendingByAccountId(accountId: Int): LinkPending? = findPending("central/discord/pending_find_by_account.sql", accountId)

    fun findPendingForDiscordUser(discordUserId: Long): LinkPending? =
        findPending("central/discord/pending_find_by_discord_user.sql", discordUserId.toString())

    fun attachVerificationMessage(discordUserId: Long, channelId: Long, messageId: Long) {
        verificationMessages[discordUserId] = VerificationMessageRef(channelId, messageId)
    }

    fun verificationMessageFor(discordUserId: Long): VerificationMessageRef? = verificationMessages[discordUserId]

    fun recordWrongAttempt(accountId: Int): Int = incrementWrongAttempts(accountId)

    fun completeLink(accountId: Int, discordUserId: Long): LinkCompleteResult {
        val accountDiscordId = findDiscordIdByAccountId(accountId)
        if (accountDiscordId == discordUserId) {
            clearPendingState(accountId, discordUserId)
            return LinkCompleteResult.AlreadyLinked
        }

        val updated =
            runCatching { updateDiscordId(accountId, discordUserId) }
                .getOrElse { return LinkCompleteResult.Failed }
        if (!updated) {
            return LinkCompleteResult.Failed
        }

        clearPendingState(accountId, discordUserId)
        return LinkCompleteResult.Linked
    }

    fun accountDiscordId(accountId: Int): Long? = findDiscordIdByAccountId(accountId)

    fun generateCodeGrid(correctCode: Int): List<Int> {
        val codes = linkedSetOf(correctCode)
        while (codes.size < config.codeButtonCount) {
            codes.add(Random.nextInt(1000, 10_000))
        }
        return codes.shuffled()
    }

    private fun clearPendingState(accountId: Int, discordUserId: Long) {
        deletePendingByAccountId(accountId)
        verificationMessages.remove(discordUserId)
    }

    private fun insertPending(
        accountId: Int,
        discordUserId: Long,
        code: Int,
        expiresAt: Instant,
    ) {
        val sql = OpenRuneSql.text("central/discord/pending_insert.sql")
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, accountId)
                ps.setString(2, discordUserId.toString())
                ps.setInt(3, code)
                ps.setTimestamp(4, Timestamp.from(expiresAt))
                ps.executeUpdate()
            }
        }
    }

    private fun findPending(sqlResource: String, bindValue: Any): LinkPending? {
        val sql = OpenRuneSql.text(sqlResource)
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                when (bindValue) {
                    is Int -> ps.setInt(1, bindValue)
                    is String -> ps.setString(1, bindValue)
                    else -> error("Unsupported bind type: ${bindValue::class.simpleName}")
                }
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    LinkPending(
                        accountId = rs.getInt("account_id"),
                        discordUserId = rs.getString("discord_user_id").toLong(),
                        code = rs.getInt("code"),
                        wrongAttempts = rs.getInt("wrong_attempts"),
                        expiresAt = rs.getTimestamp("expires_at").toInstant(),
                        createdAt = rs.getTimestamp("created_at").toInstant(),
                    )
                }
            }
        }
    }

    private fun deletePendingByAccountId(accountId: Int) {
        val sql = OpenRuneSql.text("central/discord/pending_delete_by_account.sql")
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, accountId)
                ps.executeUpdate()
            }
        }
    }

    private fun deletePendingByDiscordUserId(discordUserId: Long) {
        val sql = OpenRuneSql.text("central/discord/pending_delete_by_discord_user.sql")
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, discordUserId.toString())
                ps.executeUpdate()
            }
        }
    }

    private fun incrementWrongAttempts(accountId: Int): Int {
        val sql = OpenRuneSql.text("central/discord/pending_increment_wrong_attempts.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, accountId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) 0 else rs.getInt(1)
                }
            }
        }
    }

    private fun findDiscordIdByAccountId(accountId: Int): Long? {
        val sql = OpenRuneSql.text("central/discord/find_discord_id_by_account.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, accountId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) null else rs.getString("discord_id")?.toLongOrNull()
                }
            }
        }
    }

    private fun updateDiscordId(accountId: Int, discordUserId: Long): Boolean {
        val sql = OpenRuneSql.text("central/discord/update_discord_id.sql")
        return try {
            dataSource.connection.use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, discordUserId.toString())
                    ps.setInt(2, accountId)
                    ps.executeUpdate() == 1
                }
            }
        } catch (error: Exception) {
            log.error(
                "Failed to update discord_id for accountId={} discordUserId={}: {}",
                accountId,
                discordUserId,
                error.message,
            )
            throw error
        }
    }
}

data class LinkPending(
    val accountId: Int,
    val discordUserId: Long,
    val code: Int,
    val wrongAttempts: Int,
    val expiresAt: Instant,
    val createdAt: Instant,
)

data class VerificationMessageRef(
    val channelId: Long,
    val messageId: Long,
)

enum class LinkCompleteResult {
    Linked,
    AlreadyLinked,
    Failed,
}
