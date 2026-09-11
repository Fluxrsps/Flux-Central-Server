package dev.or2.central.vote

import dev.or2.sql.OpenRuneSql
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * The game's reads of the vote tables, plus the one write it owns.
 *
 * Votes themselves are recorded by the website, which is what the vote sites call back to. Claiming
 * is here because rewards are only ever handed out in game.
 */
class CentralVoteRepository(
    private val dataSource: DataSource,
) {
    /**
     * Claims everything a character has waiting, returning what was taken, or null when there was
     * nothing outstanding.
     *
     * Every pending vote is marked claimed, but only those inside the claim window are paid for.
     * The window lives in `vote_settings`, so this cannot disagree with what the website showed -
     * see [CentralVoteClaim.expired].
     */
    fun claimRewards(characterId: Int, claimedRewards: Int): CentralVoteClaim? {
        if (characterId <= 0) {
            return null
        }
        dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                var cleared = 0
                val claimable = mutableListOf<Instant>()
                conn.prepareStatement(OpenRuneSql.text("central/vote/claim_rewards.sql")).use { ps ->
                    ps.setInt(1, characterId)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            cleared++
                            if (rs.getBoolean(2)) {
                                claimable += rs.getTimestamp(1).toInstant()
                            }
                        }
                    }
                }
                if (cleared == 0) {
                    conn.rollback()
                    return null
                }
                var streakDays = 0
                var bestStreak = 0
                conn.prepareStatement(OpenRuneSql.text("central/vote/claim_streak_rewards.sql")).use { ps ->
                    ps.setInt(1, claimedRewards)
                    ps.setInt(2, characterId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            streakDays = rs.getInt(1)
                            bestStreak = rs.getInt(2)
                        }
                    }
                }
                conn.commit()

                return CentralVoteClaim(
                    votes = claimable.size,
                    days = claimable.map { it.atZone(ZoneOffset.UTC).toLocalDate() }.distinct().size,
                    expired = cleared - claimable.size,
                    streakDays = streakDays,
                    bestStreak = bestStreak,
                )
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    /**
     * A character's whole voting state, read straight from the tables the website writes.
     *
     * [CentralVoteState.streakDays] is what the votes earned; whether that streak is still alive is
     * the caller's call from [CentralVoteState.lastVoteDay], since only it knows the current day.
     */
    fun loadState(characterId: Int): CentralVoteState? {
        if (characterId <= 0) {
            return null
        }
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text("central/vote/load_state.sql")).use { ps ->
                ps.setInt(1, characterId)
                ps.setInt(2, characterId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return null
                    }
                    return CentralVoteState(
                        streakDays = rs.getInt("streak_days"),
                        bestStreak = rs.getInt("best_streak"),
                        lastVoteDay = rs.getInt("last_vote_day"),
                        claimedRewards = rs.getInt("claimed_rewards"),
                        totalVotes = rs.getInt("total_votes"),
                        unclaimedVotes = rs.getInt("unclaimed_votes"),
                        unclaimedDays = rs.getInt("unclaimed_days"),
                        cooldowns = cooldowns(characterId),
                    )
                }
            }
        }
    }

    private fun cooldowns(characterId: Int): Map<String, Int> {
        val remaining = HashMap<String, Int>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text("central/vote/load_site_cooldowns.sql")).use {
                ps ->
                ps.setInt(1, characterId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        remaining[rs.getString("site")] = rs.getInt("seconds_remaining")
                    }
                }
            }
        }
        return remaining
    }

    fun topAllTime(limit: Int): List<CentralVoteRow> {
        val rows = mutableListOf<CentralVoteRow>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text("central/vote/top_all_time.sql")).use { ps ->
                ps.setInt(1, limit)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        rows += CentralVoteRow(rs.getString(1), rs.getInt(2), rows.size + 1)
                    }
                }
            }
        }
        return rows
    }

    fun topMonthly(limit: Int, period: String = currentPeriod()): List<CentralVoteRow> {
        val rows = mutableListOf<CentralVoteRow>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text("central/vote/top_monthly.sql")).use { ps ->
                ps.setString(1, period)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        rows += CentralVoteRow(rs.getString(1), rs.getInt(2), rows.size + 1)
                    }
                }
            }
        }
        return rows
    }

    fun rankAllTime(characterId: Int): CentralVoteRank? =
        rankOf("central/vote/rank_all_time.sql", characterId, null)

    fun rankMonthly(characterId: Int, period: String = currentPeriod()): CentralVoteRank? =
        rankOf("central/vote/rank_monthly.sql", characterId, period)

    private fun rankOf(sql: String, characterId: Int, period: String?): CentralVoteRank? {
        if (characterId <= 0) {
            return null
        }
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text(sql)).use { ps ->
                ps.setInt(1, characterId)
                if (period != null) {
                    ps.setString(2, period)
                }
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return null
                    }
                    return CentralVoteRank(rs.getInt(1), rs.getInt(2))
                }
            }
        }
    }

    companion object {
        fun currentPeriod(): String = YearMonth.now().toString()
    }
}

data class CentralVoteRow(val displayName: String, val votes: Int, val rank: Int)

data class CentralVoteRank(val rank: Int, val votes: Int)

/**
 * What a claim took. [expired] is votes that were cleared but not paid for, having been cast before
 * the claim window.
 */
data class CentralVoteClaim(
    val votes: Int,
    val days: Int,
    val expired: Int,
    val streakDays: Int,
    val bestStreak: Int,
)

/**
 * A character's voting state. [cooldowns] holds only the sites still shut, keyed by their slug,
 * with the seconds left before each reopens.
 */
data class CentralVoteState(
    val streakDays: Int,
    val bestStreak: Int,
    val lastVoteDay: Int,
    val claimedRewards: Int,
    val totalVotes: Int,
    val unclaimedVotes: Int,
    val unclaimedDays: Int,
    val cooldowns: Map<String, Int>,
)
