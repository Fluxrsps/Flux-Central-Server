package dev.or2.central.discord

import dev.or2.central.stats.CombatLevel
import dev.or2.central.stats.SkillDefinitions
import dev.or2.sql.OpenRuneSql
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.sql.DataSource

class DiscordStatsService(
    private val dataSource: DataSource,
    private val rateLimitMillis: Long = RATE_LIMIT_MILLIS,
    private val cardCacheTtlMillis: Long = CARD_CACHE_TTL_MILLIS,
) {
    private val lastUsedByDiscordUser = ConcurrentHashMap<Long, Long>()
    private val cardCache = ConcurrentHashMap<String, CachedCard>()

    fun remainingCooldownMillis(discordUserId: Long): Long {
        val lastUsed = lastUsedByDiscordUser[discordUserId] ?: return 0L
        val elapsed = System.currentTimeMillis() - lastUsed
        return (rateLimitMillis - elapsed).coerceAtLeast(0L)
    }

    fun markUsed(discordUserId: Long) {
        lastUsedByDiscordUser[discordUserId] = System.currentTimeMillis()
    }

    fun formatCooldown(remainingMillis: Long): String {
        val totalSeconds = ((remainingMillis + 999) / 1000).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return when {
            minutes > 0 && seconds > 0 -> "${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    fun resolveProfile(
        discordUserId: Long,
        username: String?,
    ): StatsProfile? {
        val trimmed = username?.trim().orEmpty()
        if (trimmed.isNotEmpty()) {
            return findCharacterByUsername(trimmed)
        }
        return findCharacterByDiscordId(discordUserId)
    }

    fun buildCard(profile: StatsProfile): StatsCard {
        val stats = loadStats(profile.characterId)
        val snapshot = buildSnapshot(profile, stats)
        val cached = cardCache[snapshot.fingerprint]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.createdAtMillis <= cardCacheTtlMillis) {
            return StatsCard(snapshot = snapshot, pngBytes = cached.pngBytes)
        }

        val pngBytes = renderCard(snapshot)
        cardCache[snapshot.fingerprint] = CachedCard(pngBytes = pngBytes, createdAtMillis = now)
        return StatsCard(snapshot = snapshot, pngBytes = pngBytes)
    }

    private fun findCharacterByUsername(username: String): StatsProfile? {
        val sql = OpenRuneSql.text("central/stats/find_character_by_username.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, username)
                ps.setString(2, username)
                ps.setString(3, username)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    mapProfile(rs)
                }
            }
        }
    }

    private fun findCharacterByDiscordId(discordUserId: Long): StatsProfile? {
        val sql = OpenRuneSql.text("central/stats/find_character_by_discord_id.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, discordUserId.toString())
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    mapProfile(rs)
                }
            }
        }
    }

    private fun loadStats(characterId: Int): List<StatRow> {
        val sql = OpenRuneSql.text("game/stats/select_for_character.sql")
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, characterId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                StatRow(
                                    statId = rs.getInt("stat_id"),
                                    baseLevel = rs.getInt("base_level"),
                                    fineXp = rs.getInt("fine_xp"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun mapProfile(rs: java.sql.ResultSet): StatsProfile {
        val updatedAt = rs.getTimestamp("stats_updated_at")
        return StatsProfile(
            characterId = rs.getInt("character_id"),
            displayName = rs.getString("display_name"),
            accountName = rs.getString("account_name"),
            statsUpdatedAt = updatedAt?.toInstant() ?: Instant.EPOCH,
        )
    }

    private fun buildSnapshot(
        profile: StatsProfile,
        stats: List<StatRow>,
    ): StatsSnapshot {
        val levelsById = stats.associate { it.statId to it.baseLevel }
        fun level(statId: Int): Int = levelsById[statId] ?: 1

        val skills =
            SkillDefinitions.orderedSkillIds.map { statId ->
                SkillLine(name = SkillDefinitions.name(statId), level = level(statId))
            }

        val statsPart =
            stats
                .sortedBy { it.statId }
                .joinToString(",") { "${it.statId}:${it.baseLevel}:${it.fineXp}" }
        val fingerprint = "${profile.characterId}|${profile.statsUpdatedAt.toEpochMilli()}|$statsPart"

        return StatsSnapshot(
            displayName = profile.displayName?.takeIf { it.isNotBlank() } ?: profile.accountName,
            accountName = profile.accountName,
            combatLevel =
                CombatLevel.calculate(
                    attack = level(0),
                    defence = level(1),
                    strength = level(2),
                    hitpoints = level(3),
                    ranged = level(4),
                    magic = level(6),
                    prayer = level(5),
                ),
            totalLevel = skills.sumOf { it.level },
            skills = skills,
            fingerprint = fingerprint,
        )
    }

    private fun renderCard(snapshot: StatsSnapshot): ByteArray {
        val width = 720
        val height = 520
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        g.paint = GradientPaint(0f, 0f, Color(18, 22, 34), 0f, height.toFloat(), Color(10, 12, 20))
        g.fillRect(0, 0, width, height)

        g.color = Color(255, 204, 77)
        g.font = Font("SansSerif", Font.BOLD, 28)
        g.drawString("Fluxious Stats", 28, 42)

        g.color = Color(230, 233, 240)
        g.font = Font("SansSerif", Font.BOLD, 22)
        g.drawString(snapshot.displayName, 28, 78)

        g.color = Color(160, 168, 184)
        g.font = Font("SansSerif", Font.PLAIN, 14)
        g.drawString("Account: ${snapshot.accountName}", 28, 100)

        g.color = Color(120, 180, 255)
        g.font = Font("SansSerif", Font.BOLD, 16)
        g.drawString("Combat ${snapshot.combatLevel}", 28, 128)
        g.drawString("Total ${snapshot.totalLevel}", 160, 128)

        g.color = Color(70, 78, 96)
        g.fillRoundRect(24, 142, width - 48, height - 166, 18, 18)

        val columns = 3
        val cellWidth = (width - 72) / columns
        val cellHeight = (height - 190) / 8
        snapshot.skills.forEachIndexed { index, skill ->
            val x = 36 + (index % columns) * cellWidth
            val y = 158 + (index / columns) * cellHeight

            g.color = Color(34, 40, 54)
            g.fillRoundRect(x, y, cellWidth - 10, cellHeight - 8, 12, 12)

            g.color = Color(210, 214, 224)
            g.font = Font("SansSerif", Font.PLAIN, 13)
            g.drawString(skill.name, x + 12, y + 22)

            g.color = Color(255, 204, 77)
            g.font = Font("SansSerif", Font.BOLD, 18)
            g.drawString(skill.level.toString(), x + cellWidth - 42, y + 24)
        }

        g.dispose()
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return output.toByteArray()
    }

    private data class CachedCard(
        val pngBytes: ByteArray,
        val createdAtMillis: Long,
    )

    companion object {
        private const val RATE_LIMIT_MILLIS: Long = 3 * 60 * 1000L
        private const val CARD_CACHE_TTL_MILLIS: Long = 10 * 60 * 1000L
    }
}

data class StatsProfile(
    val characterId: Int,
    val displayName: String?,
    val accountName: String,
    val statsUpdatedAt: Instant,
)

data class StatsSnapshot(
    val displayName: String,
    val accountName: String,
    val combatLevel: Int,
    val totalLevel: Int,
    val skills: List<SkillLine>,
    val fingerprint: String,
)

data class SkillLine(
    val name: String,
    val level: Int,
)

data class StatsCard(
    val snapshot: StatsSnapshot,
    val pngBytes: ByteArray,
)

private data class StatRow(
    val statId: Int,
    val baseLevel: Int,
    val fineXp: Int,
)
