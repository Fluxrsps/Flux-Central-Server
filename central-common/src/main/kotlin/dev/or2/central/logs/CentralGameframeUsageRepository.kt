package dev.or2.central.logs

import dev.or2.sql.OpenRuneSql
import javax.sql.DataSource

/**
 * Which display mode each character logs in on.
 *
 * A tally rather than a log. The only question anyone asks of it is "which frame is used most", and
 * a row per login would grow with playtime to answer something that is always a sum - see
 * `V28__gameframe_usage.sql` for why it is counted at login and why a character's first is skipped.
 */
class CentralGameframeUsageRepository(
    private val dataSource: DataSource,
) {
    fun recordLogin(
        characterId: Int,
        gameframe: Int,
    ) {
        if (characterId <= 0 || gameframe <= 0) {
            return
        }
        val sql = OpenRuneSql.text("central/gameframe/record_login.sql")
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, characterId)
                ps.setInt(2, gameframe)
                ps.executeUpdate()
            }
        }
    }
}
