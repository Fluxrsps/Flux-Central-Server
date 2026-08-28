package dev.or2.central.logs

import java.sql.Timestamp
import java.sql.Types
import javax.sql.DataSource
import dev.or2.sql.OpenRuneSql

/**
 * Names of the things being counted in `central_metric_events`.
 *
 * Here rather than at either caller so the game server and anything reading the table are working
 * from one list. A metric is only ever compared for equality, so the names are the whole contract.
 */
object CentralMetrics {
    /** A login, counted against the display mode the player logged in on. */
    const val GAMEFRAME: String = "gameframe"
}

/**
 * Append-only counter events, timestamped.
 *
 * The general half of the counting: a tally answers "which is most", this answers "which was most
 * last Tuesday", and only the second can show a metric moving after an update. See
 * `V29__metric_events.sql`.
 *
 * Deliberately knows nothing about what it is counting. A new metric is a new name passed to
 * [record] and a query on the far side; nothing in this class or its table changes for one.
 */
class CentralMetricEventRepository(
    private val dataSource: DataSource,
) {
    fun record(
        metric: String,
        subject: Int,
        characterId: Int?,
        worldId: Int,
        occurredAtEpochMillis: Long,
    ) {
        val sql = OpenRuneSql.text("central/metric/insert_event.sql")
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, metric)
                ps.setInt(2, subject)
                // Null rather than 0 for a metric about the world rather than a player: the column
                // is a foreign key, and 0 is not a character.
                if (characterId == null || characterId <= 0) {
                    ps.setNull(3, Types.INTEGER)
                } else {
                    ps.setInt(3, characterId)
                }
                ps.setInt(4, worldId)
                // Stamped by the caller, not by the database. The write happens on a background
                // thread and can queue behind others; `CURRENT_TIMESTAMP` would record when it was
                // written rather than when it happened.
                ps.setTimestamp(5, Timestamp(occurredAtEpochMillis))
                ps.executeUpdate()
            }
        }
    }
}
