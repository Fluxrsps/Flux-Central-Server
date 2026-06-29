package dev.or2.central.server.session

import dev.or2.central.account.AccountNameAuthPolicy
import dev.or2.central.account.AccountRepository
import dev.or2.roles.LegacyModLevelMigration
import dev.or2.central.account.AccountNameWorldLinkPrecheck
import dev.or2.central.account.AccountNameWorldLinkPrecheckResult
import dev.or2.central.account.PasswordHasher
import dev.or2.central.util.sha256
import dev.or2.central.server.net.codec.FrameInput
import dev.or2.central.server.net.codec.WorldServerInboundPacket
import dev.or2.central.server.net.codec.readInboundPacketPayload
import dev.or2.central.server.net.codec.writeHeartbeatAck
import dev.or2.central.server.net.codec.writeHelloAck
import dev.or2.central.server.net.codec.writeHelloReject
import dev.or2.central.server.net.codec.writeLoginFail
import dev.or2.central.server.net.codec.writeLoginOk
import dev.or2.central.server.net.codec.writeLogoutAck
import dev.or2.central.server.net.codec.writePushSubscribeAck
import dev.or2.central.server.net.codec.writeSocialFail
import dev.or2.central.server.net.codec.writeSocialOk
import dev.or2.central.social.CentralSocialRepository
import dev.or2.central.server.net.codec.writeServerPrivateMessage
import dev.or2.central.server.net.push.WorldServerPushChannelRegistry
import dev.or2.central.server.net.codec.SocialSyncFriendWire
import dev.or2.central.server.net.codec.SocialSyncIgnoreWire
import dev.or2.central.server.net.codec.writeSocialSyncFail
import dev.or2.central.server.net.codec.writeSocialSyncOk
import dev.or2.central.server.net.protocol.WorldServerOpcodes
import dev.or2.central.server.telemetry.WorldServerTelemetry
import dev.or2.central.account.PunishmentLoginBlock
import dev.or2.central.account.PunishmentService
import dev.or2.central.http.world.WorldKeyVerifier
import dev.or2.central.http.world.WorldListCache
import dev.or2.central.http.world.WorldLoginGateRepository
import dev.or2.central.http.world.WorldRepository
import dev.or2.central.WorldOperationRepository
import dev.or2.central.server.net.codec.WorldServerInboundFrameSpecs
import dev.or2.central.server.net.codec.writeServerFriendPresence
import dev.or2.sql.OpenRuneSql
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import java.security.SecureRandom
import java.sql.Connection
import java.sql.Types
import javax.sql.DataSource
import org.slf4j.LoggerFactory

class WorldServerSessionService(
    private val dataSource: DataSource,
    private val worldRepository: WorldRepository,
    private val worldKeyVerifier: WorldKeyVerifier,
    private val socialRepository: CentralSocialRepository,
    private val worldServerPushChannelRegistry: WorldServerPushChannelRegistry,
    private val accountRepository: AccountRepository,
    private val passwordHasher: PasswordHasher,
    private val sessionRepository: WorldSessionRepository,
    private val worldListCache: WorldListCache?,
    private val punishmentService: PunishmentService,
    private val worldOperationRepository: WorldOperationRepository,
    private val worldLoginGateRepository: WorldLoginGateRepository,
    private val discordLinkHandler: WorldServerDiscordLinkHandler? = null,
    private val telemetry: WorldServerTelemetry = WorldServerTelemetry.None,
    private val badWordRoots: () -> Set<String> = { emptySet() },
) {
    private val log = LoggerFactory.getLogger(WorldServerSessionService::class.java)
    private val random = SecureRandom()

    /** Test and tooling helper; production path uses [WorldServerInboundPacket] from Netty. */
    fun handle(
        state: WorldServerConnectionState,
        rawPayload: ByteArray,
    ): WorldServerHandleResult = handle(state, WorldServerInboundPacket(rawPayload))

    fun handle(
        state: WorldServerConnectionState,
        packet: WorldServerInboundPacket,
    ): WorldServerHandleResult {
        val payload = packet.content
        if (payload.isEmpty()) {
            telemetry.recordInboundRejected(-1, "empty_packet")
            return WorldServerHandleResult.CloseSilent
        }
        val input = readInboundPacketPayload(payload)
        val bodyLen = input.remainingAfterOpcode
        val shapeErr = WorldServerInboundFrameSpecs.validateInboundBody(input.opcode, bodyLen)
        if (shapeErr != null) {
            telemetry.recordInboundRejected(input.opcode, shapeErr)
            return rejectBadShape(state)
        }
        telemetry.recordInboundValidated(input.opcode)
        return when (input.opcode) {
            WorldServerOpcodes.OP_WORLD_HELLO -> handleHello(state, input)
            WorldServerOpcodes.OP_LOGIN -> handleLogin(state, input)
            WorldServerOpcodes.OP_PUSH_SUBSCRIBE -> handlePushSubscribe(state, input)
            WorldServerOpcodes.OP_HEARTBEAT -> handleHeartbeat(state, input)
            WorldServerOpcodes.OP_LOGOUT -> handleLogout(state, input)
            WorldServerOpcodes.OP_GAME_DISCORD_LINK_PENDING -> handleGameDiscordLinkPending(state, input)
            WorldServerOpcodes.OP_GAME_DISCORD_LINK_INVALIDATE -> handleGameDiscordLinkInvalidate(state, input)
            WorldServerOpcodes.OP_GAME_ANNOUNCEMENT -> handleGameAnnouncement(state, input)
            WorldServerOpcodes.OP_WORLD_PM_RELAY -> handleWorldPmRelay(state, input)
            WorldServerOpcodes.OP_WORLD_FRIEND_ADD -> handleSocialNameAction(state, input)
            WorldServerOpcodes.OP_WORLD_FRIEND_DEL -> handleSocialNameAction(state, input)
            WorldServerOpcodes.OP_WORLD_IGNORE_ADD -> handleSocialNameAction(state, input)
            WorldServerOpcodes.OP_WORLD_IGNORE_DEL -> handleSocialNameAction(state, input)
            WorldServerOpcodes.OP_WORLD_CHAT_FILTERS -> handleChatFilters(state, input)
            WorldServerOpcodes.OP_WORLD_SOCIAL_SYNC -> handleSocialSync(state, input)
            else -> {
                telemetry.recordInboundRejected(input.opcode, "unhandled_opcode")
                if (!state.handshakeDone) {
                    WorldServerHandleResult.Reply(
                        writeHelloReject(WorldServerOpcodes.HELLO_REASON_PROTOCOL),
                        closeAfterWrite = true,
                    )
                } else {
                    WorldServerHandleResult.Reply(
                        writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_BAD_FRAME),
                        closeAfterWrite = false,
                    )
                }
            }
        }
    }

    private fun rejectBadShape(state: WorldServerConnectionState): WorldServerHandleResult =
        if (!state.handshakeDone) {
            WorldServerHandleResult.Reply(
                writeHelloReject(WorldServerOpcodes.HELLO_REASON_PROTOCOL),
                closeAfterWrite = true,
            )
        } else {
            WorldServerHandleResult.Reply(
                writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_BAD_FRAME),
                closeAfterWrite = false,
            )
        }

    private fun handleHello(
        state: WorldServerConnectionState,
        input: FrameInput,
    ): WorldServerHandleResult {
        if (state.handshakeDone) {
            return WorldServerHandleResult.Reply(
                writeHelloReject(WorldServerOpcodes.HELLO_REASON_PROTOCOL),
                closeAfterWrite = true,
            )
        }
        val magic = input.readMagic()
        if (magic != WorldServerOpcodes.MAGIC) {
            return WorldServerHandleResult.Reply(
                writeHelloReject(WorldServerOpcodes.HELLO_REASON_PROTOCOL),
                closeAfterWrite = true,
            )
        }
        val version = input.readUnsignedShortCompat()
        if (version < WorldServerOpcodes.MIN_CLIENT_PROTOCOL_VERSION ||
            version > WorldServerOpcodes.MAX_CLIENT_PROTOCOL_VERSION
        ) {
            return WorldServerHandleResult.Reply(
                writeHelloReject(WorldServerOpcodes.HELLO_REASON_PROTOCOL),
                closeAfterWrite = true,
            )
        }
        state.protocolVersion = version
        val worldId = input.readIntCompat()
        val keyLen = input.readUnsignedShortCompat()
        if (keyLen > WorldServerInboundFrameSpecs.WORLD_KEY_MAX_BYTES) {
            return WorldServerHandleResult.Reply(
                writeHelloReject(WorldServerOpcodes.HELLO_REASON_PROTOCOL),
                closeAfterWrite = true,
            )
        }
        val keyBytes = input.readFully(keyLen)
        if (input.trailingUnreadBytes() != 0) {
            return WorldServerHandleResult.Reply(
                writeHelloReject(WorldServerOpcodes.HELLO_REASON_PROTOCOL),
                closeAfterWrite = true,
            )
        }
        val row =
            worldRepository.findForAuth(worldId)
                ?: return WorldServerHandleResult.Reply(
                    writeHelloReject(WorldServerOpcodes.HELLO_REASON_UNKNOWN_WORLD),
                    closeAfterWrite = true,
                )
        if (!row.enabled) {
            return WorldServerHandleResult.Reply(
                writeHelloReject(WorldServerOpcodes.HELLO_REASON_WORLD_DISABLED),
                closeAfterWrite = true,
            )
        }
        if (!worldKeyVerifier.verify(row, keyBytes)) {
            return WorldServerHandleResult.Reply(
                writeHelloReject(WorldServerOpcodes.HELLO_REASON_BAD_KEY),
                closeAfterWrite = true,
            )
        }
        state.worldId = worldId
        state.maxPlayers = row.maxPlayers
        state.loginRestrictionsEnabled = row.loginRestrictionsEnabled
        state.loginMinTotalLevel = row.loginMinTotalLevel.coerceAtLeast(0)
        state.loginMinRightsToken = row.loginMinRightsToken
        state.loginGateMinLevelEnabled = row.loginGateMinLevelEnabled
        state.loginGateRightsEnabled = row.loginGateRightsEnabled
        state.loginGateWhitelistEnabled = row.loginGateWhitelistEnabled
        state.realmDevMode = row.realmDevMode
        state.handshakeDone = true
        return WorldServerHandleResult.Reply(writeHelloAck())
    }

    private fun handlePushSubscribe(
        state: WorldServerConnectionState,
        input: FrameInput,
    ): WorldServerHandleResult {
        if (!state.handshakeDone) {
            return WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_NOT_HANDSHAKEN))
        }
        if (input.trailingUnreadBytes() != 0) {
            return WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_BAD_FRAME))
        }
        state.subscribedForServerPush = true
        return WorldServerHandleResult.Reply(writePushSubscribeAck())
    }

    private fun handleLogin(
        state: WorldServerConnectionState,
        input: FrameInput,
    ): WorldServerHandleResult {
        if (!state.handshakeDone) {
            return WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_NOT_HANDSHAKEN))
        }
        val username = input.readUtf8LenPrefixed()
        val password = input.readUtf8LenPrefixed()
        val trailingAfterCreds = input.trailingUnreadBytes()
        val loginCharacterId: Int? =
            when {
                trailingAfterCreds == 0 -> null
                state.protocolVersion >= 4 && trailingAfterCreds == 4 -> input.readIntCompat()
                else ->
                    return WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_BAD_FRAME))
            }
        if (input.trailingUnreadBytes() != 0) {
            return WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_BAD_FRAME))
        }
        if (password.length > 256) {
            return WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_BAD_FRAME))
        }
        val roots = badWordRoots()
        val canonical =
            when (val pre = AccountNameWorldLinkPrecheck.evaluateRawUsernameForLogin(username, roots)) {
                AccountNameWorldLinkPrecheckResult.BadFrame ->
                    return WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_BAD_FRAME))
                is AccountNameWorldLinkPrecheckResult.Policy -> {
                    val lines = AccountNameAuthPolicy.policyScriptLines(pre.reason)
                    // Protocol defines LOGIN_FAIL_ACCOUNT_NAME (16), but some game builds only attach LOGIN_FAIL
                    // script UI for world-gate-style codes (e.g. WORLD_ACCESS). Unknown codes map to invalid creds.
                    return WorldServerHandleResult.Reply(
                        writeLoginFail(
                            WorldServerOpcodes.LOGIN_FAIL_WORLD_ACCESS,
                            state.protocolVersion,
                            lines,
                        ),
                    )
                }
                is AccountNameWorldLinkPrecheckResult.Ok -> pre.canonical
            }
        val loginKey = canonical.lowercase()
        var accounts = accountRepository.findByUsername(loginKey)
        val registerNow = System.currentTimeMillis()
        if (accounts == null) {
            val ck = AccountNameAuthPolicy.collisionKey(canonical)
            if (ck.isNotEmpty() && accountRepository.collisionKeyTaken(ck)) {
                return WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_INVALID))
            }
            val hash = passwordHasher.hash(password)
            accountRepository.insertIfAbsent(loginKey, hash, registerNow)
            accounts = accountRepository.findByUsername(loginKey)
        }
        if (accounts == null || !passwordHasher.verify(accounts.passwordHash, password)) {
            return WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_INVALID))
        }
        val effectiveCharId = loginCharacterId?.takeIf { it > 0 }
        if (effectiveCharId != null &&
            !punishmentService.characterBelongsToAccount(effectiveCharId, accounts.id)
        ) {
            return WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_BAD_FRAME))
        }
        when (
            punishmentService.blockingLoginSanction(
                accounts.id,
                effectiveCharId,
            )
        ) {
            PunishmentLoginBlock.LOCKED ->
                return WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_LOCKED))
            PunishmentLoginBlock.BANNED ->
                return WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_BANNED))
            null -> Unit
        }
        if (state.loginRestrictionsEnabled) {
            val deny =
                evaluateWorldLoginDeny(
                    state = state,
                    loginUsername = loginKey,
                    accountRights = accounts.rights,
                    accountId = accounts.id,
                    characterId = effectiveCharId,
                )
            if (deny != null) {
                return WorldServerHandleResult.Reply(
                    writeLoginFail(
                        deny.code,
                        state.protocolVersion,
                        Triple(deny.line1, deny.line2, deny.line3),
                    ),
                )
            }
        }
        if (worldOperationRepository.shouldBlockLoginForScheduledReboot(state.worldId, System.currentTimeMillis())) {
            return WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_UPDATE_IN_PROGRESS))
        }
        val max = state.maxPlayers
        val token = ByteArray(WorldServerOpcodes.TOKEN_BYTES).also { random.nextBytes(it) }
        val tokenHash = sha256(token)
        val now = System.currentTimeMillis()
        try {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    deleteSessionsForAccount(conn, accounts.id)
                    if (max != null && countSessionsForWorld(conn, state.worldId) >= max) {
                        conn.rollback()
                        return WorldServerHandleResult.Reply(
                            writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_WORLD_FULL),
                        )
                    }
                    insertSession(conn, accounts.id, state.worldId, effectiveCharId, tokenHash, now)
                    conn.commit()
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = true
                }
            }
        } catch (e: Exception) {
            log.warn("Login transaction failed (worldId={})", state.worldId, e)
            return WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_BAD_FRAME))
        }
        worldListCache?.rebuild()
        telemetry.recordLoginSuccess()
        effectiveCharId?.let {
            fanoutFriendPresence(
                friendCharacterId = it,
                onlineWorldId = state.worldId,
                displayedWorldId = state.worldId,
            )
        }
        val rightsForGame = effectiveRightsForWorldLogin(state.realmDevMode, accounts.rights)
        return WorldServerHandleResult.Reply(
            writeLoginOk(token, accounts.id, rightsForGame, state.protocolVersion),
        )
    }

    private fun handleHeartbeat(
        state: WorldServerConnectionState,
        input: FrameInput,
    ): WorldServerHandleResult {
        if (!state.handshakeDone) {
            return WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_NOT_HANDSHAKEN))
        }
        val token = readSessionTokenStrict(input) ?: return loginBadFrameReply()
        val row = sessionRepository.findByTokenHash(sha256(token)) ?: return failBadToken()
        if (row.worldId != state.worldId) {
            return failBadToken()
        }
        sessionRepository.touchById(row.id, System.currentTimeMillis())
        return WorldServerHandleResult.Reply(writeHeartbeatAck())
    }

    private fun handleGameDiscordLinkPending(
        state: WorldServerConnectionState,
        input: FrameInput,
    ): WorldServerHandleResult {
        if (!state.handshakeDone) {
            return WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_NOT_HANDSHAKEN))
        }
        val handler = discordLinkHandler
            ?: return WorldServerHandleResult.Reply(
                dev.or2.central.server.net.codec.writeGameDiscordLinkPendingFail(
                    WorldServerOpcodes.GAME_DISCORD_LINK_PENDING_FAIL_UNAVAILABLE,
                ),
                closeAfterWrite = true,
            )
        val accountId = input.readIntCompat()
        val discordUsername = input.readUtf8LenPrefixed()
        if (input.trailingUnreadBytes() != 0) {
            return handler.handlePending(accountId = 0, discordUsername = "")
        }
        return handler.handlePending(accountId, discordUsername)
    }

    private fun handleGameDiscordLinkInvalidate(
        state: WorldServerConnectionState,
        input: FrameInput,
    ): WorldServerHandleResult {
        if (!state.handshakeDone) {
            return WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_NOT_HANDSHAKEN))
        }
        val handler = discordLinkHandler
            ?: return WorldServerHandleResult.Reply(
                dev.or2.central.server.net.codec.writeGameDiscordLinkPendingFail(
                    WorldServerOpcodes.GAME_DISCORD_LINK_PENDING_FAIL_UNAVAILABLE,
                ),
                closeAfterWrite = true,
            )
        val accountId = input.readIntCompat()
        if (input.trailingUnreadBytes() != 0) {
            return handler.handleInvalidate(0)
        }
        return handler.handleInvalidate(accountId)
    }

    private fun handleGameAnnouncement(
        state: WorldServerConnectionState,
        input: FrameInput,
    ): WorldServerHandleResult {
        if (!state.handshakeDone) {
            return WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_NOT_HANDSHAKEN))
        }
        val message = input.readUtf8LenPrefixed()
        if (input.trailingUnreadBytes() != 0) {
            return loginBadFrameReply()
        }
        if (message.isBlank()) {
            return loginBadFrameReply()
        }
        worldOperationRepository.insertBroadcastLog(
            worldId = null,
            message = message,
            url = "",
            icon = "",
            createdBy = "world-${state.worldId}",
        )
        return WorldServerHandleResult.Reply(
            dev.or2.central.server.net.codec.writeGameAnnouncementAck(),
        )
    }

    private fun handleLogout(
        state: WorldServerConnectionState,
        input: FrameInput,
    ): WorldServerHandleResult {
        if (!state.handshakeDone) {
            return WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_NOT_HANDSHAKEN))
        }
        val token = readSessionTokenStrict(input) ?: return loginBadFrameReply()
        val row = sessionRepository.findByTokenHash(sha256(token)) ?: return failBadToken()
        if (row.worldId != state.worldId) {
            return failBadToken()
        }
        row.characterId?.let {
            fanoutFriendPresence(
                friendCharacterId = it,
                onlineWorldId = row.worldId,
                displayedWorldId = 0,
            )
        }
        sessionRepository.deleteById(row.id)
        worldListCache?.rebuild()
        return WorldServerHandleResult.Reply(writeLogoutAck(), closeAfterWrite = true)
    }

    private fun handleWorldPmRelay(
        state: WorldServerConnectionState,
        input: FrameInput,
    ): WorldServerHandleResult {
        if (!state.handshakeDone) {
            return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)
        }

        val token = readSessionTokenForSocial(input)
            ?: return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)

        val row = sessionRepository.findByTokenHash(sha256(token))
            ?: return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)

        if (row.worldId != state.worldId) {
            return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)
        }

        sessionRepository.touchById(row.id, System.currentTimeMillis())

        val fromCharacterId = input.readIntCompat()
        val senderCrown = input.readUnsignedByteCompat()
        val targetName = input.readUtf8LenPrefixed().trim()
        val senderDisplayName = input.readUtf8LenPrefixed().trim()
        val message = input.readUtf8LenPrefixed().trim()

        if (row.characterId != null && row.characterId != fromCharacterId) {
            return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)
        }

        if (input.trailingUnreadBytes() != 0 || fromCharacterId <= 0 || targetName.isBlank() || message.isBlank()) {
            return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)
        }

        val target = socialRepository.findCharacterByDisplayName(targetName)
            ?: return socialFail(WorldServerOpcodes.SOCIAL_FAIL_USER_NOT_FOUND)

        if (target.characterId == fromCharacterId) {
            return socialFail(WorldServerOpcodes.SOCIAL_FAIL_SELF_ACTION)
        }

        if (socialRepository.isIgnored(target.characterId, fromCharacterId)) {
            return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ACCEPTING_PRIVATE)
        }

        val filters = socialRepository.chatFilters(target.characterId)

        // OSRS-style assumption: 0 = on, 1 = friends, 2 = off.
        if (filters.privateChat >= 2) {
            return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ACCEPTING_PRIVATE)
        }

        if (filters.privateChat == 1 && !socialRepository.isFriend(target.characterId, fromCharacterId)) {
            return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ACCEPTING_PRIVATE)
        }

        val targetSession = socialRepository.onlineSessionForCharacter(target.characterId)
            ?: return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_LOGGED_IN)

        val targetChannel = worldServerPushChannelRegistry.channel(targetSession.worldId)
            ?: return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_LOGGED_IN)

        val frame =
            writeServerPrivateMessage(
                senderWorldId = state.worldId,
                fromCharacterId = fromCharacterId,
                toCharacterId = target.characterId,
                senderDisplayName = senderDisplayName.ifBlank { "Player" },
                senderCrown = senderCrown,
                message = message,
            )

        writePushFrame(targetChannel, frame)

        return WorldServerHandleResult.Reply(writeSocialOk())
    }

    private fun handleSocialNameAction(
        state: WorldServerConnectionState,
        input: FrameInput,
    ): WorldServerHandleResult {
        if (!state.handshakeDone) {
            return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)
        }

        val token = readSessionTokenForSocial(input)
            ?: return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)

        val row = sessionRepository.findByTokenHash(sha256(token))
            ?: return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)

        if (row.worldId != state.worldId) {
            return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)
        }

        sessionRepository.touchById(row.id, System.currentTimeMillis())

        val characterId = input.readIntCompat()
        val targetName = input.readUtf8LenPrefixed().trim()

        if (row.characterId != null && row.characterId != characterId) {
            return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)
        }

        if (input.trailingUnreadBytes() != 0 || characterId <= 0 || targetName.isBlank()) {
            return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)
        }

        val target = socialRepository.findCharacterByDisplayName(targetName)
            ?: return socialFail(WorldServerOpcodes.SOCIAL_FAIL_USER_NOT_FOUND)

        if (target.characterId == characterId) {
            return socialFail(WorldServerOpcodes.SOCIAL_FAIL_SELF_ACTION)
        }

        return when (input.opcode) {
            WorldServerOpcodes.OP_WORLD_FRIEND_ADD -> {
                if (socialRepository.isFriend(characterId, target.characterId)) {
                    socialFail(WorldServerOpcodes.SOCIAL_FAIL_ALREADY_FRIEND)
                } else if (socialRepository.isIgnored(characterId, target.characterId)) {
                    socialFail(WorldServerOpcodes.SOCIAL_FAIL_ALREADY_IGNORED)
                } else {
                    val beforeVisible =
                        visiblePresenceRecipients(
                            friendCharacterId = characterId,
                            onlineWorldId = row.worldId,
                        )

                    socialRepository.addFriend(characterId, target.characterId)

                    val afterVisible =
                        visiblePresenceRecipients(
                            friendCharacterId = characterId,
                            onlineWorldId = row.worldId,
                        )

                    sendPresenceVisibilityChanges(beforeVisible, afterVisible)

                    WorldServerHandleResult.Reply(writeSocialOk())
                }
            }

            WorldServerOpcodes.OP_WORLD_FRIEND_DEL -> {
                val beforeVisible =
                    visiblePresenceRecipients(
                        friendCharacterId = characterId,
                        onlineWorldId = row.worldId,
                    )

                socialRepository.deleteFriend(characterId, target.characterId)

                val afterVisible =
                    visiblePresenceRecipients(
                        friendCharacterId = characterId,
                        onlineWorldId = row.worldId,
                    )

                sendPresenceVisibilityChanges(beforeVisible, afterVisible)

                WorldServerHandleResult.Reply(writeSocialOk())
            }

            WorldServerOpcodes.OP_WORLD_IGNORE_ADD -> {
                if (socialRepository.isIgnored(characterId, target.characterId)) {
                    socialFail(WorldServerOpcodes.SOCIAL_FAIL_ALREADY_IGNORED)
                } else if (socialRepository.isFriend(characterId, target.characterId)) {
                    socialFail(WorldServerOpcodes.SOCIAL_FAIL_ALREADY_FRIEND)
                } else {
                    val beforeVisible =
                        visiblePresenceRecipients(
                            friendCharacterId = characterId,
                            onlineWorldId = row.worldId,
                        )

                    socialRepository.addIgnore(characterId, target.characterId)

                    val afterVisible =
                        visiblePresenceRecipients(
                            friendCharacterId = characterId,
                            onlineWorldId = row.worldId,
                        )

                    sendPresenceVisibilityChanges(beforeVisible, afterVisible)

                    WorldServerHandleResult.Reply(writeSocialOk())
                }
            }

            WorldServerOpcodes.OP_WORLD_IGNORE_DEL -> {
                val beforeVisible =
                    visiblePresenceRecipients(
                        friendCharacterId = characterId,
                        onlineWorldId = row.worldId,
                    )

                socialRepository.deleteIgnore(characterId, target.characterId)

                val afterVisible =
                    visiblePresenceRecipients(
                        friendCharacterId = characterId,
                        onlineWorldId = row.worldId,
                    )

                sendPresenceVisibilityChanges(beforeVisible, afterVisible)

                WorldServerHandleResult.Reply(writeSocialOk())
            }

            else -> socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)
        }
    }

    // Central social only owns private-chat visibility. Public/trade bytes are still
    // consumed to keep the world-link frame aligned with the client packet shape.
    private fun handleChatFilters(
        state: WorldServerConnectionState,
        input: FrameInput,
    ): WorldServerHandleResult {
        if (!state.handshakeDone) {
            return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)
        }

        val token = readSessionTokenForSocial(input)
            ?: return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)

        val row = sessionRepository.findByTokenHash(sha256(token))
            ?: return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)

        if (row.worldId != state.worldId) {
            return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)
        }

        sessionRepository.touchById(row.id, System.currentTimeMillis())

        val characterId = input.readIntCompat()
        input.readUnsignedByteCompat() // public chat; owned by the game server, ignored here
        val privateChat = input.readUnsignedByteCompat()
        input.readUnsignedByteCompat() // trade chat; owned by trade/social systems later, ignored here

        if (row.characterId != null && row.characterId != characterId) {
            return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)
        }

        if (input.trailingUnreadBytes() != 0 || characterId <= 0) {
            return socialFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)
        }

        val beforeVisible =
            visiblePresenceRecipients(
                friendCharacterId = characterId,
                onlineWorldId = row.worldId,
            )

        socialRepository.setPrivateChatFilter(
            characterId = characterId,
            privateChat = privateChat,
        )

        val afterVisible =
            visiblePresenceRecipients(
                friendCharacterId = characterId,
                onlineWorldId = row.worldId,
            )

        sendPresenceVisibilityChanges(beforeVisible, afterVisible)

        return WorldServerHandleResult.Reply(writeSocialOk())
    }

    private fun readSessionTokenForSocial(input: FrameInput): ByteArray? {
        val tokenLen = input.readUnsignedShortCompat()
        if (tokenLen != WorldServerOpcodes.TOKEN_BYTES) {
            return null
        }
        return input.readFully(tokenLen)
    }

    private fun handleSocialSync(
        state: WorldServerConnectionState,
        input: FrameInput,
    ): WorldServerHandleResult {
        if (!state.handshakeDone) {
            return WorldServerHandleResult.Reply(
                writeSocialSyncFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)
            )
        }

        val token = readSessionTokenForSocial(input)
            ?: return WorldServerHandleResult.Reply(
                writeSocialSyncFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)
            )

        val row = sessionRepository.findByTokenHash(sha256(token))
            ?: return WorldServerHandleResult.Reply(
                writeSocialSyncFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)
            )

        if (row.worldId != state.worldId) {
            return WorldServerHandleResult.Reply(
                writeSocialSyncFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)
            )
        }

        sessionRepository.touchById(row.id, System.currentTimeMillis())

        val characterId = input.readIntCompat()

        if (row.characterId != null && row.characterId != characterId) {
            return WorldServerHandleResult.Reply(
                writeSocialSyncFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)
            )
        }

        if (input.trailingUnreadBytes() != 0 || characterId <= 0) {
            return WorldServerHandleResult.Reply(
                writeSocialSyncFail(WorldServerOpcodes.SOCIAL_FAIL_NOT_ALLOWED)
            )
        }

        val snapshot = socialRepository.snapshot(characterId)

        return WorldServerHandleResult.Reply(
            writeSocialSyncOk(
                publicChat = snapshot.filters.publicChat,
                privateChat = snapshot.filters.privateChat,
                tradeChat = snapshot.filters.tradeChat,
                friends =
                    snapshot.friends.map {
                        SocialSyncFriendWire(
                            displayName = it.displayName,
                            previousDisplayName = it.previousDisplayName,
                            worldId = it.worldId,
                        )
                    },
                ignores =
                    snapshot.ignores.map {
                        SocialSyncIgnoreWire(
                            displayName = it.displayName,
                            previousDisplayName = it.previousDisplayName,
                        )
                    },
            )
        )
    }

    private fun fanoutFriendPresence(
        friendCharacterId: Int,
        onlineWorldId: Int,
        displayedWorldId: Int,
    ) {
        val recipients =
            socialRepository.friendPresenceRecipients(
                friendCharacterId = friendCharacterId,
                onlineWorldId = onlineWorldId,
            )

        for (recipient in recipients) {
            if (recipient.visibleWorldId <= 0) {
                continue
            }

            sendFriendPresence(
                recipient = recipient,
                displayedWorldId = if (displayedWorldId > 0) recipient.visibleWorldId else 0,
            )
        }
    }

    private fun writePushFrame(
        channel: Channel,
        frame: ByteArray,
    ) {
        channel.eventLoop().execute {
            if (!channel.isActive) {
                return@execute
            }

            channel.writeAndFlush(Unpooled.wrappedBuffer(frame))
        }
    }

    private fun sendFriendPresence(
        recipient: dev.or2.central.social.FriendPresenceRecipientRow,
        displayedWorldId: Int,
    ) {
        val channel = worldServerPushChannelRegistry.channel(recipient.ownerWorldId) ?: return

        val frame =
            writeServerFriendPresence(
                ownerCharacterId = recipient.ownerCharacterId,
                friendCharacterId = recipient.friendCharacterId,
                friendWorldId = displayedWorldId,
                friendDisplayName = recipient.friendDisplayName,
                friendPreviousDisplayName = recipient.friendPreviousDisplayName,
            )

        writePushFrame(channel, frame)
    }

    private fun visiblePresenceRecipients(
        friendCharacterId: Int,
        onlineWorldId: Int,
    ): Map<Int, dev.or2.central.social.FriendPresenceRecipientRow> {
        val recipients =
            socialRepository.friendPresenceRecipients(
                friendCharacterId = friendCharacterId,
                onlineWorldId = onlineWorldId,
            )

        return recipients
            .filter { recipient -> recipient.visibleWorldId > 0 }
            .associateBy { recipient -> recipient.ownerCharacterId }
    }

    private fun sendPresenceVisibilityChanges(
        beforeVisible: Map<Int, dev.or2.central.social.FriendPresenceRecipientRow>,
        afterVisible: Map<Int, dev.or2.central.social.FriendPresenceRecipientRow>,
    ) {
        for ((ownerCharacterId, before) in beforeVisible) {
            if (ownerCharacterId !in afterVisible) {
                sendFriendPresence(
                    recipient = before,
                    displayedWorldId = 0,
                )
            }
        }

        for ((ownerCharacterId, after) in afterVisible) {
            if (ownerCharacterId !in beforeVisible) {
                sendFriendPresence(
                    recipient = after,
                    displayedWorldId = after.visibleWorldId,
                )
            }
        }
    }

    private fun socialFail(reason: Int): WorldServerHandleResult.Reply =
        WorldServerHandleResult.Reply(writeSocialFail(reason))

    private fun failBadToken(): WorldServerHandleResult.Reply =
        WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_BAD_TOKEN))

    private fun loginBadFrameReply(): WorldServerHandleResult.Reply =
        WorldServerHandleResult.Reply(writeLoginFail(WorldServerOpcodes.LOGIN_FAIL_BAD_FRAME))

    private data class WorldGateDeny(
        val code: Int,
        val line1: String,
        val line2: String,
        val line3: String,
    )

    private fun evaluateWorldLoginDeny(
        state: WorldServerConnectionState,
        loginUsername: String,
        accountRights: Int,
        accountId: Long,
        characterId: Int?,
    ): WorldGateDeny? {
        if (!state.loginRestrictionsEnabled) {
            return null
        }
        val minRequired = state.loginMinTotalLevel.coerceAtLeast(0)
        val anyLevel = state.loginGateMinLevelEnabled && minRequired > 0
        val anyRights = state.loginGateRightsEnabled && !state.loginMinRightsToken.isNullOrBlank()
        val hasWhitelistRows = worldLoginGateRepository.hasLoginWhitelistEntries(state.worldId)
        val anyWhitelist = state.loginGateWhitelistEnabled && hasWhitelistRows
        if (!anyLevel && !anyRights && !anyWhitelist) {
            return null
        }
        var ok = false
        val total: Int? =
            if (anyLevel && characterId != null) {
                worldLoginGateRepository.characterTotalBaseLevel(characterId, accountId)
            } else {
                null
            }
        if (anyLevel && characterId != null && (total ?: 0) >= minRequired) {
            ok = true
        }
        if (!ok && anyWhitelist && worldLoginGateRepository.isLoginWhitelisted(state.worldId, loginUsername)) {
            ok = true
        }
        if (!ok && anyRights) {
            val t = state.loginMinRightsToken!!
            if (LegacyModLevelMigration.rightsLevelMeetsModLevelToken(accountRights, t)) {
                ok = true
            }
        }
        if (ok) {
            return null
        }
        val tok = (state.loginMinRightsToken ?: "").trim()
        if (anyLevel && (characterId == null || (total ?: 0) < minRequired)) {
            val line1 = "You need a total level of at least $minRequired."
            val line2 =
                if (characterId == null) {
                    "This world could not verify your character's skills on login."
                } else {
                    "Your total level is ${total ?: 0}."
                }
            val line3 =
                if (characterId == null) {
                    "Select your character before logging in, or update your game server."
                } else {
                    "Train your skills, then try again."
                }
            return WorldGateDeny(WorldServerOpcodes.LOGIN_FAIL_WORLD_MIN_LEVEL, line1, line2, line3)
        }
        if (anyWhitelist) {
            return WorldGateDeny(
                WorldServerOpcodes.LOGIN_FAIL_WORLD_WHITELIST,
                "This world is restricted.",
                "Your account is not on the login whitelist.",
                "Contact staff if you believe this is a mistake.",
            )
        }
        if (anyRights) {
            return WorldGateDeny(
                WorldServerOpcodes.LOGIN_FAIL_WORLD_RIGHTS,
                "This world requires special account access.",
                """You need "${tok.substringAfter(".")}" access to log in.""",
                "",
            )
        }
        return WorldGateDeny(
            WorldServerOpcodes.LOGIN_FAIL_WORLD_ACCESS,
            "You cannot log in to this world right now.",
            "World login rules blocked this account.",
            "",
        )
    }

    /**
     * Dev realms (`realms.dev_mode`): LOGIN_OK always sends `modlevel.owner` so the game treats the
     * player as owner regardless of stored `accounts.rights`. World login gates still use DB rights.
     */
    private fun effectiveRightsForWorldLogin(
        realmDevMode: Boolean,
        accountRights: Int,
    ): String {
        if (realmDevMode) {
            return "modlevel.owner"
        }
        return LegacyModLevelMigration.rightsWireFromLevel(accountRights)
    }

    private fun readSessionTokenStrict(input: FrameInput): ByteArray? {
        val tokenLen = input.readUnsignedShortCompat()
        if (tokenLen != WorldServerOpcodes.TOKEN_BYTES) return null
        val token = input.readFully(tokenLen)
        if (input.trailingUnreadBytes() != 0) return null
        return token
    }

    private fun deleteSessionsForAccount(
        conn: Connection,
        accountId: Long,
    ) {
        val sql = OpenRuneSql.text("central/session/delete_by_account_id.sql")
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, accountId)
            ps.executeUpdate()
        }
    }

    private fun countSessionsForWorld(
        conn: Connection,
        worldId: Int,
    ): Int {
        val sql = OpenRuneSql.text("central/session/count_for_world.sql")
        return conn.prepareStatement(sql).use { ps ->
            ps.setInt(1, worldId)
            ps.executeQuery().use { rs ->
                require(rs.next())
                rs.getInt(1)
            }
        }
    }

    private fun insertSession(
        conn: Connection,
        accountId: Long,
        worldId: Int,
        characterId: Int?,
        tokenHash: ByteArray,
        now: Long,
    ) {
        val sql = OpenRuneSql.text("central/session/insert.sql")
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, accountId)
            ps.setInt(2, worldId)
            if (characterId != null) {
                ps.setInt(3, characterId)
            } else {
                ps.setNull(3, Types.INTEGER)
            }
            ps.setBytes(4, tokenHash)
            ps.setLong(5, now)
            ps.setLong(6, now)
            ps.executeUpdate()
        }
    }
}
