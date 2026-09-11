package dev.or2.central.vote

import dev.or2.sql.OpenRuneSql
import java.time.YearMonth
import javax.sql.DataSource

/**
 * Vote tallies for the highscore boards.
 *
 * Two tallies rather than a row per vote: every question asked of this data is a sum or a rank, and
 * a row per vote would grow with playtime to answer it. The monthly table is partitioned by period
 * so a finished month is never rewritten, and rolls over without a scheduled reset - see
 * `V34__vote_highscores.sql`.
 */
class CentralVoteRepository(
    private val dataSource: DataSource,
) {
    fun recordVotes(
        characterId: Int,
        displayName: String,
        votes: Int,
        period: String = currentPeriod(),
    ) {
        if (characterId <= 0 || votes <= 0) {
            return
        }
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text("central/vote/record_vote.sql")).use { ps ->
                ps.setInt(1, characterId)
                ps.setString(2, displayName)
                ps.setInt(3, votes)
                ps.executeUpdate()
            }
            conn.prepareStatement(OpenRuneSql.text("central/vote/record_vote_monthly.sql")).use { ps ->
                ps.setInt(1, characterId)
                ps.setString(2, period)
                ps.setString(3, displayName)
                ps.setInt(4, votes)
                ps.executeUpdate()
            }
        }
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
