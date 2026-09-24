package dev.or2.central.wellofgoodwill

import dev.or2.sql.OpenRuneSql
import javax.sql.DataSource

/** The pot as it stands: which cycle is running and how much is in it. */
data class CentralWellState(val cycleId: Long, val total: Long)

/** One character's donated total and the rank that goes with it, on whichever board was asked for. */
data class CentralWellStanding(val rank: Int, val amount: Long)

data class CentralWellDonator(val displayName: String, val amount: Long)

/**
 * What a donation did: the totals either side of it and the cycle it ended on.
 *
 * [before] and [after] come from inside the locked transaction, so the world that gets them is the
 * only one that can see a milestone being crossed - which is what stops two worlds handing out the
 * same reward.
 */
data class CentralWellDonation(
    val accepted: Long,
    val before: Long,
    val after: Long,
    val cycleId: Long,
)

/**
 * The Well of Goodwill, shared by every world.
 *
 * The game owns this outright: it takes donations, crosses the milestones and rolls the cycle over.
 * The caller supplies the rules - how much the well will accept at a given total, and how a donation
 * splits across a cycle boundary - because those belong to the game's content, not the schema.
 */
class CentralWellOfGoodwillRepository(private val dataSource: DataSource) {
    fun loadState(): CentralWellState? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text("central/well_of_goodwill/load_state.sql")).use { ps ->
                ps.executeQuery().use { rs ->
                    if (rs.next()) CentralWellState(rs.getLong(1), rs.getLong(2)) else null
                }
            }
        }

    /**
     * Applies as much of [amount] as [capacity] allows to the locked pot, crediting [characterId]
     * for each part [split] puts in a cycle. Returns null when the state row is missing.
     */
    fun donate(
        characterId: Int,
        displayName: String,
        amount: Long,
        goal: Long,
        capacity: (Long) -> Long,
        split: (Long, Long) -> List<Long>,
    ): CentralWellDonation? {
        if (characterId <= 0 || amount <= 0) {
            return null
        }
        dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val locked =
                    conn.prepareStatement(OpenRuneSql.text("central/well_of_goodwill/lock_state.sql"))
                        .use { ps ->
                            ps.executeQuery().use { rs ->
                                if (rs.next()) CentralWellState(rs.getLong(1), rs.getLong(2)) else null
                            }
                        }
                if (locked == null) {
                    conn.rollback()
                    return null
                }

                val accepted = minOf(amount, capacity(locked.total))
                if (accepted <= 0) {
                    conn.rollback()
                    return CentralWellDonation(0, locked.total, locked.total, locked.cycleId)
                }

                var cycleId = locked.cycleId
                var total = locked.total
                for (chunk in split(locked.total, accepted)) {
                    if (chunk > 0) {
                        credit(conn, cycleId, characterId, displayName, chunk)
                        total += chunk
                    }
                    if (total >= goal) {
                        total = 0
                        cycleId += 1
                    }
                }

                conn.prepareStatement(OpenRuneSql.text("central/well_of_goodwill/update_state.sql")).use { ps ->
                    ps.setLong(1, cycleId)
                    ps.setLong(2, total)
                    ps.executeUpdate()
                }
                conn.commit()
                return CentralWellDonation(accepted, locked.total, total, cycleId)
            } catch (e: Exception) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = previousAutoCommit }
            }
        }
    }

    fun startNewCycle(): CentralWellState? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text("central/well_of_goodwill/start_new_cycle.sql")).use { ps ->
                ps.executeQuery().use { rs ->
                    if (rs.next()) CentralWellState(rs.getLong(1), rs.getLong(2)) else null
                }
            }
        }

    fun topCycle(cycleId: Long, limit: Int): List<CentralWellDonator> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text("central/well_of_goodwill/top_cycle.sql")).use { ps ->
                ps.setLong(1, cycleId)
                ps.setInt(2, limit)
                ps.executeQuery().use(::readDonators)
            }
        }

    fun topAllTime(limit: Int): List<CentralWellDonator> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text("central/well_of_goodwill/top_all_time.sql")).use { ps ->
                ps.setInt(1, limit)
                ps.executeQuery().use(::readDonators)
            }
        }

    fun standing(characterId: Int, cycleId: Long?): CentralWellStanding {
        if (characterId <= 0) {
            return CentralWellStanding(0, 0)
        }
        val sql =
            if (cycleId == null) {
                OpenRuneSql.text("central/well_of_goodwill/standing_all_time.sql")
            } else {
                OpenRuneSql.text("central/well_of_goodwill/standing_cycle.sql")
            }
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                if (cycleId == null) {
                    ps.setInt(1, characterId)
                } else {
                    ps.setLong(1, cycleId)
                    ps.setLong(2, cycleId)
                    ps.setInt(3, characterId)
                }
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        CentralWellStanding(rs.getInt("rank"), rs.getLong("amount"))
                    } else {
                        CentralWellStanding(0, 0)
                    }
                }
            }
        }
    }

    private fun credit(
        conn: java.sql.Connection,
        cycleId: Long,
        characterId: Int,
        displayName: String,
        amount: Long,
    ) {
        conn.prepareStatement(OpenRuneSql.text("central/well_of_goodwill/credit_donation.sql")).use { ps ->
            ps.setLong(1, cycleId)
            ps.setInt(2, characterId)
            ps.setString(3, displayName)
            ps.setLong(4, amount)
            ps.executeUpdate()
        }
    }

    private fun readDonators(rs: java.sql.ResultSet): List<CentralWellDonator> {
        val out = mutableListOf<CentralWellDonator>()
        while (rs.next()) {
            out += CentralWellDonator(rs.getString(1) ?: "", rs.getLong(2))
        }
        return out
    }
}
