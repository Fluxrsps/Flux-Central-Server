package dev.or2.central

import dev.or2.central.http.world.WorldListCache
import dev.or2.central.server.net.codec.writeServerBroadcast
import dev.or2.central.server.net.codec.writeServerDisplayNameSync
import dev.or2.central.server.net.codec.writeServerKick
import dev.or2.central.server.net.codec.writeServerMuteUpdate
import dev.or2.central.server.net.codec.writeServerRebootSchedule
import dev.or2.central.server.net.codec.writeServerRevokeLogin
import dev.or2.central.server.net.push.WorldServerPushChannelRegistry
import dev.or2.central.server.session.WorldSessionRepository
import io.netty.buffer.Unpooled
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import java.sql.Connection
import javax.sql.DataSource

class PostgresEventListenerService(
    private val dataSource: DataSource,
    private val worldServerPushChannelRegistry: WorldServerPushChannelRegistry,
    private val sessionRepository: WorldSessionRepository,
    private val worldListCache: WorldListCache?,
) {

    private val log = LoggerFactory.getLogger(PostgresEventListenerService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var stopped = true
    @Volatile private var listenConnection: Connection? = null

    private var thread: Thread? = null

    fun start() {
        if (!stopped) return
        stopped = false

        thread = Thread({
            try {
                runListenLoop()
            } catch (e: Exception) {
                if (!stopped) log.error("Punishment LISTEN thread failed", e)
            }
        }, "openrune-punishment-pg-listen").apply {
            isDaemon = true
        }

        thread!!.start()
    }

    fun stop() {
        stopped = true
        thread?.interrupt()

        runCatching { listenConnection?.close() }

        listenConnection = null
        thread = null
    }

    private fun runListenLoop() {
        val conn = dataSource.connection.also {
            it.autoCommit = true
            listenConnection = it
        }

        val pg = runCatching { conn.unwrap(PGConnection::class.java) }
            .getOrElse {
                log.warn("PostgreSQL LISTEN disabled: {}", it.message)
                conn.close()
                listenConnection = null
                return
            }

        conn.createStatement().use { st ->
            st.addBatch("LISTEN punishment_events")
            st.addBatch("LISTEN punishment_kick_events")
            st.addBatch("LISTEN character_mute_events")
            st.addBatch("LISTEN world_reboot_events")
            st.addBatch("LISTEN world_broadcast_events")
            st.addBatch("LISTEN character_display_name_events")
            st.executeBatch()
        }

        log.info("Listening for PostgreSQL NOTIFY events")

        conn.createStatement().use { ping ->
            while (!stopped && !Thread.currentThread().isInterrupted) {

                runCatching { ping.execute("SELECT 1") }
                    .onFailure {
                        if (stopped) return
                        log.warn("LISTEN poll failed: {}", it.message)
                        Thread.sleep(500)
                        return@use
                    }

                pg.notifications?.forEach { n ->
                    when (n.name) {
                        "punishment_events" -> handlePunishment(n.parameter)
                        "punishment_kick_events" -> handleKick(n.parameter)
                        "character_mute_events" -> handleMute(n.parameter)
                        "world_reboot_events" -> handleReboot(n.parameter)
                        "world_broadcast_events" -> handleBroadcast(n.parameter)
                        "character_display_name_events" -> handleDisplayName(n.parameter)
                    }
                }

                Thread.sleep(200)
            }
        }
    }

    private fun parseObject(payload: String?) =
        payload?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun JsonObject.long(name: String) =
        this[name]?.jsonPrimitive?.content?.toLongOrNull()

    private fun JsonObject.int(name: String) =
        this[name]?.jsonPrimitive?.content?.toIntOrNull()

    private fun JsonObject.string(name: String, default: String = "") =
        this[name]?.jsonPrimitive?.content ?: default

    private fun flush(worldIds: List<Int>, frame: ByteArray, broadcastAll: Boolean) {
        val targets =
            when {
                worldIds.isNotEmpty() -> worldIds
                broadcastAll -> worldServerPushChannelRegistry.registeredWorldIds()
                else -> emptyList()
            }

        targets.forEach { worldId ->
            worldServerPushChannelRegistry.channel(worldId)?.let { ch ->
                ch.eventLoop().execute {
                    if (ch.isActive) {
                        ch.writeAndFlush(Unpooled.wrappedBuffer(frame))
                    }
                }
            }
        }
    }

    private fun handlePunishment(raw: String?) {
        val root = parseObject(raw) ?: return

        val accountId = root.long("account_id") ?: return
        val characterId = root.int("character_id") ?: 0

        runCatching {
            val worlds = sessionRepository.findDistinctWorldIdsByAccount(accountId)
            sessionRepository.deleteAllForAccount(accountId)

            worldListCache?.rebuild()

            val frame = writeServerRevokeLogin(accountId, characterId)

            if (worlds.isEmpty()) {
                log.info("No sessions found for {}, broadcasting revoke", accountId)
            }

            flush(worlds, frame, broadcastAll = true)
        }.onFailure {
            log.warn("Failed punishment revoke for {}", accountId, it)
        }
    }

    private fun handleKick(raw: String?) {
        val root = parseObject(raw) ?: return

        val accountId = root.long("account_id") ?: return
        val characterId = root.int("character_id") ?: 0

        runCatching {
            val worlds = sessionRepository.findDistinctWorldIdsByAccount(accountId)
            val frame = writeServerKick(accountId, characterId)
            flush(worlds, frame, broadcastAll = true)
        }.onFailure {
            log.warn("Kick failed for {}", accountId, it)
        }
    }

    private fun handleMute(raw: String?) {
        val root = parseObject(raw) ?: return

        val accountId = root.long("account_id") ?: return
        val characterId = root.int("character_id") ?: return
        val until = root.long("muted_until_epoch_millis") ?: return

        runCatching {
            val worlds = sessionRepository.findDistinctWorldIdsByAccount(accountId)
            val frame = writeServerMuteUpdate(accountId, characterId, until)
            flush(worlds, frame, broadcastAll = true)
        }.onFailure {
            log.warn("Mute failed for {}", accountId, it)
        }
    }

    private fun handleReboot(raw: String?) {
        val root = parseObject(raw) ?: return

        val op = root.string("op")
        val worldId = root.int("world_id")
        val scope = worldId ?: 0

        val frame = when (op) {
            "clear" -> writeServerRebootSchedule(true, scope, 0L, "")
            "set" -> {
                val at = root.long("reboot_at_ms") ?: return
                val msg = root.string("message")
                writeServerRebootSchedule(false, scope, at, msg)
            }
            else -> return
        }

        flush(if (worldId != null) listOf(worldId) else emptyList(), frame, broadcastAll = worldId == null)
    }

    private fun handleBroadcast(raw: String?) {
        val root = parseObject(raw) ?: return

        val worldId = root.int("world_id")
        val scope = worldId ?: 0

        val message = root.string("message")
        val url = root.string("url")
        val icon = root.string("icon")

        val frame = writeServerBroadcast(scope, message, url, icon)

        flush(if (worldId != null) listOf(worldId) else emptyList(), frame, broadcastAll = worldId == null)
    }

    private fun handleDisplayName(raw: String?) {
        val root = parseObject(raw) ?: return

        val accountId = root.long("account_id") ?: return
        val characterId = root.int("character_id") ?: return
        val displayName = root.string("display_name")
        val previousDisplayName = root.string("previous_display_name")

        runCatching {
            val worlds = sessionRepository.findDistinctWorldIdsByAccount(accountId)
            val frame = writeServerDisplayNameSync(accountId, characterId, displayName, previousDisplayName)
            flush(worlds, frame, broadcastAll = true)
        }.onFailure {
            log.warn("Display name push failed for account {}", accountId, it)
        }
    }
}