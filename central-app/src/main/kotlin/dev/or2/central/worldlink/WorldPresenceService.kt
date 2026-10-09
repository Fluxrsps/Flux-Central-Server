package dev.or2.central.worldlink

import dev.or2.central.db.FlywayMigrator
import dev.or2.central.db.repositories.SessionRepository
import dev.or2.central.http.WorldListCache
import dev.or2.central.social.SocialService
import dev.or2.sql.OpenRuneSql
import javax.sql.DataSource

/** Clears Central sessions and game character online markers when a world-link connection drops. */
class WorldPresenceService(
    private val dataSource: DataSource,
    private val sessionRepository: SessionRepository,
    private val worldListCache: WorldListCache?,
    private val socialService: SocialService,
) {
    fun onPushChannelAttached(worldId: Int) {
        socialService.pruneStalePresenceForWorld(worldId)
    }

    fun onWorldDisconnected(worldId: Int) {
        if (worldId <= 0) {
            return
        }
        for (session in sessionRepository.listByWorldId(worldId)) {
            val characterId = session.characterId ?: continue
            socialService.onCharacterOffline(worldId, characterId)
        }
        val sessionsRemoved = sessionRepository.deleteByWorldId(worldId)
        val charactersCleared = clearCharacterOnlineMarkers(worldId)
        if (sessionsRemoved > 0 || charactersCleared > 0) {
            worldListCache?.rebuild()
        }
    }

    /**
     * Player saves live in a schema per world type, so `character_progress` only resolves with one on
     * the search path. Central does not know which modes a world served, so it clears every mode -
     * the `world_id` predicate already limits the update to that world's rows.
     */
    private fun clearCharacterOnlineMarkers(worldId: Int): Int {
        val sql = OpenRuneSql.text("game/character/characters_clear_online_presence_on_world.sql")
        var cleared = 0
        for (schema in FlywayMigrator.worldTypeSchemas(dataSource)) {
            cleared +=
                dataSource.connection.use { conn ->
                    // `SET LOCAL` needs a real transaction; on the pool's autocommit connection a
                    // plain `SET` would leak this search path into the next borrower.
                    conn.autoCommit = false
                    try {
                        conn.createStatement().use { st ->
                            st.execute("SET LOCAL search_path TO \"$schema\", public")
                        }
                        val updated =
                            conn.prepareStatement(sql).use { ps ->
                                ps.setInt(1, worldId)
                                ps.executeUpdate()
                            }
                        conn.commit()
                        updated
                    } catch (e: Exception) {
                        conn.rollback()
                        throw e
                    } finally {
                        conn.autoCommit = true
                    }
                }
        }
        return cleared
    }
}
