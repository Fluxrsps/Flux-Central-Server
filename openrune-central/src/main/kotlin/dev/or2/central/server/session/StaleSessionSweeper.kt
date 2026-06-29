package dev.or2.central.server.session

import dev.or2.central.http.world.WorldListCache
import dev.or2.central.server.net.codec.writeServerFriendPresence
import dev.or2.central.server.net.push.WorldServerPushChannelRegistry
import dev.or2.central.social.CentralSocialRepository
import io.netty.buffer.Unpooled
import org.slf4j.LoggerFactory
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class WorldSessionReaper(
    private val sessionRepository: WorldSessionRepository,
    private val socialRepository: CentralSocialRepository,
    private val worldServerPushChannelRegistry: WorldServerPushChannelRegistry,
    private val worldListCache: WorldListCache?,
    private val ttlMillis: Long,
    private val scheduler: ScheduledExecutorService,
) {
    private val log = LoggerFactory.getLogger(WorldSessionReaper::class.java)

    fun start() {
        scheduler.scheduleAtFixedRate(
            {
                try {
                    val cutoff = System.currentTimeMillis() - ttlMillis
                    val staleSessions = sessionRepository.deleteStaleReturning(cutoff)

                    if (staleSessions.isNotEmpty()) {
                        for (session in staleSessions) {
                            val characterId = session.characterId ?: continue
                            if (session.worldId <= 0) {
                                continue
                            }

                            fanoutStaleOffline(
                                characterId = characterId,
                                oldWorldId = session.worldId,
                            )
                        }

                        log.info("Removed {} stale sessions", staleSessions.size)
                        worldListCache?.rebuild()
                    }
                } catch (e: Exception) {
                    log.warn("Session reaping failed", e)
                }
            },
            1,
            1,
            TimeUnit.MINUTES,
        )
    }

    private fun fanoutStaleOffline(
        characterId: Int,
        oldWorldId: Int,
    ) {
        val recipients =
            socialRepository.friendPresenceRecipients(
                friendCharacterId = characterId,
                onlineWorldId = oldWorldId,
            )

        for (recipient in recipients) {
            if (recipient.visibleWorldId <= 0) {
                continue
            }

            val channel = worldServerPushChannelRegistry.channel(recipient.ownerWorldId) ?: continue

            val frame =
                writeServerFriendPresence(
                    ownerCharacterId = recipient.ownerCharacterId,
                    friendCharacterId = recipient.friendCharacterId,
                    friendWorldId = 0,
                    friendDisplayName = recipient.friendDisplayName,
                    friendPreviousDisplayName = recipient.friendPreviousDisplayName,
                )

            channel.eventLoop().execute {
                if (channel.isActive) {
                    channel.writeAndFlush(Unpooled.wrappedBuffer(frame))
                }
            }
        }
    }
}