package dev.or2.central.server.session

import dev.or2.central.server.net.protocol.WorldServerOpcodes

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.or2.central.account.AccountRepository
import dev.or2.central.account.PasswordHasher
import dev.or2.central.util.sha256
import dev.or2.central.http.world.WorldKeyVerifier
import dev.or2.central.http.world.WorldLoginGateRepository
import dev.or2.central.http.world.WorldRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import javax.sql.DataSource
import dev.or2.central.CentralSchemaBootstrap
import dev.or2.central.account.PunishmentService
import dev.or2.central.WorldOperationRepository
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WorldServerSessionServiceTest {
    companion object {
        private lateinit var embedded: EmbeddedPostgres

        @JvmStatic
        @BeforeAll
        fun postgresUp() {
            embedded = EmbeddedPostgres.start()
        }

        @JvmStatic
        @AfterAll
        fun postgresDown() {
            if (::embedded.isInitialized) {
                embedded.close()
            }
        }
    }

    private lateinit var dataSource: DataSource
    private lateinit var service: WorldServerSessionService

    @BeforeEach
    fun setup() {
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    dataSource = embedded.getPostgresDatabase()
                    maximumPoolSize = 4
                    connectionInitSql = "SET application_name = 'openrune_central_test'"
                },
            )
        CentralSchemaBootstrap.apply(dataSource)

        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "TRUNCATE online_samples, activity_log_attachments, punishments, activity_logs, world_login_whitelist, world_broadcast_log, world_reboot_schedules, sessions, accounts RESTART IDENTITY CASCADE",
                )
            }
            conn.createStatement().use {
                    it.execute(
                        "UPDATE worlds SET max_players = 100, login_restrictions_enabled = 0, " +
                            "login_min_total_level = 0, login_min_rights_token = NULL, " +
                            "login_gate_min_level_enabled = 0, login_gate_rights_enabled = 0, login_gate_whitelist_enabled = 0 " +
                            "WHERE world_id = 1",
                    )
            }
        }

        val worldRepository = WorldRepository(dataSource)
        worldRepository.updateWorldKeySha256(1, sha256("test-key".toByteArray(StandardCharsets.UTF_8)))
        val worldKeyVerifier = WorldKeyVerifier()
        service =
            WorldServerSessionService(
                dataSource = dataSource,
                worldRepository = worldRepository,
                worldKeyVerifier = worldKeyVerifier,
                accountRepository = AccountRepository(dataSource),
                passwordHasher = PasswordHasher(),
                sessionRepository = WorldSessionRepository(dataSource),
                worldListCache = null,
                punishmentService = PunishmentService(dataSource),
                worldOperationRepository = WorldOperationRepository(dataSource),
                worldLoginGateRepository = WorldLoginGateRepository(dataSource),
            )
    }

    @AfterEach
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    @Test
    fun loginGateMinLevelV5IncludesScriptTrailer() {
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute(
                    "UPDATE worlds SET login_restrictions_enabled = 1, login_min_total_level = 9000, " +
                        "login_gate_min_level_enabled = 1 WHERE world_id = 1",
                )
            }
            val hash = PasswordHasher().hash("pw")
            conn.prepareStatement(
                """
                INSERT INTO accounts (account_name, password_hash, rights)
                VALUES ('gated', ?, '')
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, hash)
                ps.executeUpdate()
            }
        }
        val state = WorldServerConnectionState()
        service.handle(state, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key", clientProtocolVersion = 5))
        assertEquals(5, state.protocolVersion)
        val denied = service.handle(state, buildLoginFrame("gated", "pw")) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.OP_LOGIN_FAIL, denied.payload[0].toInt() and 0xFF)
        assertEquals(WorldServerOpcodes.LOGIN_FAIL_WORLD_MIN_LEVEL, readLoginFailCode(denied.payload))
        val lines = readLoginFailScriptLines(denied.payload)
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("9000"))
    }

    @Test
    fun loginGateMinLevelV4OmitsScriptTrailer() {
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute(
                    "UPDATE worlds SET login_restrictions_enabled = 1, login_min_total_level = 9000, " +
                        "login_gate_min_level_enabled = 1 WHERE world_id = 1",
                )
            }
            val hash = PasswordHasher().hash("pw")
            conn.prepareStatement(
                """
                INSERT INTO accounts (account_name, password_hash, rights)
                VALUES ('gated4', ?, '')
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, hash)
                ps.executeUpdate()
            }
        }
        val state = WorldServerConnectionState()
        service.handle(state, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key", clientProtocolVersion = 4))
        val denied = service.handle(state, buildLoginFrame("gated4", "pw")) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.LOGIN_FAIL_WORLD_MIN_LEVEL, readLoginFailCode(denied.payload))
        assertEquals(5, denied.payload.size)
    }

    @Test
    fun helloLoginLogout() {
        dataSource.connection.use { conn ->
            val hash = PasswordHasher().hash("secret")
            conn.prepareStatement(
                """
                INSERT INTO accounts (account_name, password_hash, rights)
                VALUES (?, ?, '')
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, "alice")
                ps.setString(2, hash)
                ps.executeUpdate()
            }
        }
        val state = WorldServerConnectionState()
        val badMagic = buildHelloFrame(999, 1, "test-key")
        val bad = service.handle(state, badMagic) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.OP_HELLO_REJECT, bad.payload[0].toInt() and 0xFF)

        val hello = buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key")
        val ack = service.handle(state, hello) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.OP_HELLO_ACK, ack.payload[0].toInt() and 0xFF)
        assertTrue(state.handshakeDone)
        assertEquals(1, state.worldId)

        val login = buildLoginFrame("alice", "secret")
        val loginOk = service.handle(state, login) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.OP_LOGIN_OK, loginOk.payload[0].toInt() and 0xFF)

        val parsed = readLoginOk(loginOk.payload)
        assertEquals(1L, parsed.accountId)
        assertEquals("modlevel.owner", parsed.rights)
        val token = parsed.token
        val hb = buildHeartbeatFrame(token)
        val hbAck = service.handle(state, hb) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.OP_HEARTBEAT_ACK, hbAck.payload[0].toInt() and 0xFF)

        val logout = buildLogoutFrame(token)
        val outAck = service.handle(state, logout) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.OP_LOGOUT_ACK, outAck.payload[0].toInt() and 0xFF)
        assertTrue(outAck.closeAfterWrite)
    }

    @Test
    fun loginRejectsTrailingBytes() {
        dataSource.connection.use { conn ->
            val hash = PasswordHasher().hash("secret")
            conn.prepareStatement(
                """
                INSERT INTO accounts (account_name, password_hash, rights)
                VALUES (?, ?, '')
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, "trail")
                ps.setString(2, hash)
                ps.executeUpdate()
            }
        }
        val state = WorldServerConnectionState()
        service.handle(state, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key"))
        val login = buildLoginFrame("trail", "secret") + byteArrayOf(0x00)
        val bad = service.handle(state, login) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.OP_LOGIN_FAIL, bad.payload[0].toInt() and 0xFF)
    }

    @Test
    fun helloV2LoginOkOmitsRightsTrailer() {
        dataSource.connection.use { conn ->
            val hash = PasswordHasher().hash("pw2")
            conn.prepareStatement(
                """
                INSERT INTO accounts (account_name, password_hash, rights)
                VALUES ('v2user', ?, 'modlevel.admin')
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, hash)
                ps.executeUpdate()
            }
        }
        val state = WorldServerConnectionState()
        service.handle(state, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key", clientProtocolVersion = 2))
        assertEquals(2, state.protocolVersion)
        val loginOk = service.handle(state, buildLoginFrame("v2user", "pw2")) as WorldServerHandleResult.Reply
        val parsed = readLoginOk(loginOk.payload)
        assertEquals("", parsed.rights)
        assertEquals(1 + 2 + WorldServerOpcodes.TOKEN_BYTES + 8, loginOk.payload.size)
    }

    @Test
    fun devRealmOverridesStoredAccountRightsWithOwner() {
        dataSource.connection.use { conn ->
            val hash = PasswordHasher().hash("secret")
            conn.prepareStatement(
                """
                INSERT INTO accounts (account_name, password_hash, rights)
                VALUES (?, ?, 'modlevel.player')
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, "rightsuser")
                ps.setString(2, hash)
                ps.executeUpdate()
            }
        }
        val state = WorldServerConnectionState()
        service.handle(state, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key"))
        val loginOk = service.handle(state, buildLoginFrame("rightsuser", "secret")) as WorldServerHandleResult.Reply
        val parsed = readLoginOk(loginOk.payload)
        assertEquals("modlevel.owner", parsed.rights)
    }

    @Test
    fun nonDevRealmDoesNotInjectOwnerRights() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("UPDATE realms SET dev_mode = 0 WHERE realm_id = 1") }
            val hash = PasswordHasher().hash("secret")
            conn.prepareStatement(
                """
                INSERT INTO accounts (account_name, password_hash, rights)
                VALUES (?, ?, '')
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, "plain")
                ps.setString(2, hash)
                ps.executeUpdate()
            }
        }
        try {
            val state = WorldServerConnectionState()
            service.handle(state, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key"))
            val loginOk = service.handle(state, buildLoginFrame("plain", "secret")) as WorldServerHandleResult.Reply
            val parsed = readLoginOk(loginOk.payload)
            assertEquals("", parsed.rights)
        } finally {
            dataSource.connection.use { conn ->
                conn.createStatement().use { it.execute("UPDATE realms SET dev_mode = 1 WHERE realm_id = 1") }
            }
        }
    }

    private fun readLoginFailCode(payload: ByteArray): Int {
        val din = DataInputStream(ByteArrayInputStream(payload))
        assertEquals(WorldServerOpcodes.OP_LOGIN_FAIL, din.readUnsignedByte())
        return din.readInt()
    }

    private fun readLoginFailScriptLines(payload: ByteArray): List<String> {
        val din = DataInputStream(ByteArrayInputStream(payload))
        assertEquals(WorldServerOpcodes.OP_LOGIN_FAIL, din.readUnsignedByte())
        din.readInt()
        return listOf(readUtf8Len(din), readUtf8Len(din), readUtf8Len(din))
    }

    private fun readUtf8Len(din: DataInputStream): String {
        val len = din.readUnsignedShort()
        val bytes = ByteArray(len)
        din.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    @Test
    fun secondLoginReplacesSessionWithoutDuplicate() {
        dataSource.connection.use { conn ->
            val hash = PasswordHasher().hash("pw")
            conn.prepareStatement(
                """
                INSERT INTO accounts (account_name, password_hash, rights)
                VALUES ('bob', ?, '')
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, hash)
                ps.executeUpdate()
            }
        }
        val state1 = WorldServerConnectionState()
        service.handle(state1, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key"))
        val first =
            service.handle(state1, buildLoginFrame("bob", "pw")) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.OP_LOGIN_OK, first.payload[0].toInt() and 0xFF)

        val state2 = WorldServerConnectionState()
        service.handle(state2, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key"))
        val second =
            service.handle(state2, buildLoginFrame("bob", "pw")) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.OP_LOGIN_OK, second.payload[0].toInt() and 0xFF)

        val sessionsRepo = WorldSessionRepository(dataSource)
        assertEquals(1, sessionsRepo.totalOnline())
    }

    @Test
    fun loginRejectedWhenAccountBanned() {
        dataSource.connection.use { conn ->
            val hash = PasswordHasher().hash("pw")
            conn.prepareStatement(
                """
                INSERT INTO accounts (account_name, password_hash, rights)
                VALUES ('banneduser', ?, '')
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, hash)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                """
                INSERT INTO punishments (
                    scope, account_id, character_id, kind, issued_at, expires_at,
                    reason, private_notes, public_notes, issued_by, approved_by, status, repo_link_uuid
                )
                SELECT
                    'account', id, NULL, 'ban', CURRENT_TIMESTAMP, NULL,
                    'test ban', NULL, 'See website', 'staff-a', 'staff-b', 'active', NULL
                FROM accounts WHERE account_name = 'banneduser'
                """.trimIndent(),
            ).use { it.executeUpdate() }
        }
        val state = WorldServerConnectionState()
        service.handle(state, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key"))
        val denied = service.handle(state, buildLoginFrame("banneduser", "pw")) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.OP_LOGIN_FAIL, denied.payload[0].toInt() and 0xFF)
        assertEquals(WorldServerOpcodes.LOGIN_FAIL_BANNED, readLoginFailCode(denied.payload))
    }

    @Test
    fun loginSucceedsWhenCharacterMutedWithV4() {
        dataSource.connection.use { conn ->
            val hash = PasswordHasher().hash("pw")
            conn.prepareStatement(
                """
                INSERT INTO accounts (account_name, password_hash, rights)
                VALUES ('charuser', ?, '')
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, hash)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                """
                WITH a AS (SELECT id FROM accounts WHERE account_name = 'charuser')
                INSERT INTO account_characters (account_id, display_name)
                SELECT id, 'punish_char_v4' FROM a
                """.trimIndent(),
            ).use { it.executeUpdate() }
            conn.prepareStatement(
                """
                INSERT INTO punishments (
                    scope, account_id, character_id, kind, issued_at, expires_at,
                    reason, private_notes, public_notes, issued_by, approved_by, status, repo_link_uuid
                )
                SELECT
                    'character', NULL, id, 'temp_mute', CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + INTERVAL '1 day',
                    'voice', NULL, 'Muted 1d', 'staff', NULL, 'active', NULL
                FROM account_characters WHERE display_name = 'punish_char_v4'
                """.trimIndent(),
            ).use { it.executeUpdate() }
        }
        val state = WorldServerConnectionState()
        service.handle(state, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key", clientProtocolVersion = 4))
        val charId =
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT id FROM account_characters WHERE display_name = 'punish_char_v4'")
                    .use { ps ->
                        ps.executeQuery().use { rs ->
                            assertTrue(rs.next())
                            rs.getInt(1)
                        }
                    }
            }
        val ok =
            service.handle(state, buildLoginFrame("charuser", "pw", loginCharacterId = charId))
                as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.OP_LOGIN_OK, ok.payload[0].toInt() and 0xFF)
    }

    @Test
    fun loginRejectedWhenAccountLocked() {
        dataSource.connection.use { conn ->
            val hash = PasswordHasher().hash("pw")
            conn.prepareStatement(
                """
                INSERT INTO accounts (account_name, password_hash, rights)
                VALUES ('lockeduser', ?, '')
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, hash)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                """
                INSERT INTO punishments (
                    scope, account_id, character_id, kind, issued_at, expires_at,
                    reason, private_notes, public_notes, issued_by, approved_by, status, repo_link_uuid
                )
                SELECT
                    'account', id, NULL, 'locked', CURRENT_TIMESTAMP, NULL,
                    'test lock', NULL, NULL, 'staff', NULL, 'active', NULL
                FROM accounts WHERE account_name = 'lockeduser'
                """.trimIndent(),
            ).use { it.executeUpdate() }
        }
        val state = WorldServerConnectionState()
        state.remoteHost = "127.0.0.1"
        service.handle(state, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key"))
        val denied =
            service.handle(state, buildLoginFrame("lockeduser", "pw")) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.OP_LOGIN_FAIL, denied.payload[0].toInt() and 0xFF)
        assertEquals(WorldServerOpcodes.LOGIN_FAIL_LOCKED, readLoginFailCode(denied.payload))
    }

    @Test
    fun loginV3RejectsFourByteCharacterTrailer() {
        dataSource.connection.use { conn ->
            val hash = PasswordHasher().hash("pw")
            conn.prepareStatement(
                """
                INSERT INTO accounts (account_name, password_hash, rights)
                VALUES ('v3trail', ?, '')
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, hash)
                ps.executeUpdate()
            }
        }
        val state = WorldServerConnectionState()
        service.handle(state, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key", clientProtocolVersion = 3))
        val login = buildLoginFrame("v3trail", "pw", loginCharacterId = 1)
        val bad = service.handle(state, login) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.OP_LOGIN_FAIL, bad.payload[0].toInt() and 0xFF)
        assertEquals(WorldServerOpcodes.LOGIN_FAIL_BAD_FRAME, readLoginFailCode(bad.payload))
    }

    @Test
    fun autoRegistersOnFirstLogin() {
        val state1 = WorldServerConnectionState()
        service.handle(state1, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key"))
        val loginOk =
            service.handle(state1, buildLoginFrame("newbie", "first-time-pass"))
                as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.OP_LOGIN_OK, loginOk.payload[0].toInt() and 0xFF)

        val state2 = WorldServerConnectionState()
        service.handle(state2, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key"))
        val denied =
            service.handle(state2, buildLoginFrame("newbie", "wrong")) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.OP_LOGIN_FAIL, denied.payload[0].toInt() and 0xFF)
    }

    @Test
    fun accountNameContainsModRejectedWithScriptTrailer() {
        val svc = createSessionServiceWithBadWords(emptySet())
        val state = WorldServerConnectionState()
        svc.handle(state, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key", clientProtocolVersion = 5))
        val denied = svc.handle(state, buildLoginFrame("abModc", "pw")) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.LOGIN_FAIL_WORLD_ACCESS, readLoginFailCode(denied.payload))
        val lines = readLoginFailScriptLines(denied.payload)
        assertTrue(lines.any { it.contains("mod", ignoreCase = true) }, lines.toString())
    }

    @Test
    fun accountNamePolicyRejectDoesNotInsertAccount() {
        val svc = createSessionServiceWithBadWords(emptySet())
        val state = WorldServerConnectionState()
        svc.handle(state, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key", clientProtocolVersion = 5))
        val denied = svc.handle(state, buildLoginFrame("abModc", "pw")) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.LOGIN_FAIL_WORLD_ACCESS, readLoginFailCode(denied.payload))
        val count =
            dataSource.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT COUNT(*) AS c FROM accounts WHERE LOWER(account_name) = LOWER('abmodc')",
                    ).use { rs ->
                        rs.next()
                        rs.getInt("c")
                    }
                }
            }
        assertEquals(0, count)
    }

    @Test
    fun accountNamePolicyRejectProtocolV2OmitsScriptTrailer() {
        val svc = createSessionServiceWithBadWords(setOf("xyzzy"))
        val state = WorldServerConnectionState()
        svc.handle(state, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key", clientProtocolVersion = 2))
        val denied = svc.handle(state, buildLoginFrame("x xyzzy x", "pw")) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.LOGIN_FAIL_WORLD_ACCESS, readLoginFailCode(denied.payload))
        assertEquals(5, denied.payload.size, "v2–v4 LOGIN_FAIL is opcode + 4-byte code only")
    }

    @Test
    fun accountNameIllegalWireCharacterRejectedWithScriptTrailer() {
        val svc = createSessionServiceWithBadWords(emptySet())
        val state = WorldServerConnectionState()
        svc.handle(state, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key", clientProtocolVersion = 5))
        val denied = svc.handle(state, buildLoginFrame("bad@name", "pw")) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.LOGIN_FAIL_WORLD_ACCESS, readLoginFailCode(denied.payload))
        val lines = readLoginFailScriptLines(denied.payload)
        assertTrue(lines.any { it.contains("odd", ignoreCase = true) }, lines.toString())
    }

    @Test
    fun accountNameProfanityUsesConfiguredRoots() {
        val svc = createSessionServiceWithBadWords(setOf("xyzzy"))
        val state = WorldServerConnectionState()
        svc.handle(state, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key", clientProtocolVersion = 5))
        val denied = svc.handle(state, buildLoginFrame("x xyzzy x", "pw")) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.LOGIN_FAIL_WORLD_ACCESS, readLoginFailCode(denied.payload))
        val lines = readLoginFailScriptLines(denied.payload)
        assertTrue(lines.any { it.contains("blocked", ignoreCase = true) }, lines.toString())
    }

    @Test
    fun collisionWithExistingCharacterDisplayNameRejectsRegistration() {
        dataSource.connection.use { conn ->
            val hash = PasswordHasher().hash("secret")
            conn.prepareStatement(
                """
                INSERT INTO accounts (account_name, password_hash, rights)
                VALUES ('holder', ?, '')
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, hash)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                """
                INSERT INTO account_characters (account_id, display_name, members)
                VALUES ((SELECT id FROM accounts WHERE account_name = 'holder'), 'DisplayHold', false)
                """.trimIndent(),
            ).use { ps ->
                ps.executeUpdate()
            }
        }
        val svc = createSessionServiceWithBadWords(emptySet())
        val state = WorldServerConnectionState()
        svc.handle(state, buildHelloFrame(WorldServerOpcodes.MAGIC, 1, "test-key"))
        val denied =
            svc.handle(state, buildLoginFrame("displayhold", "brand-new-pass")) as WorldServerHandleResult.Reply
        assertEquals(WorldServerOpcodes.LOGIN_FAIL_INVALID, readLoginFailCode(denied.payload))
    }

    private fun createSessionServiceWithBadWords(badRoots: Set<String>): WorldServerSessionService =
        WorldServerSessionService(
            dataSource = dataSource,
            worldRepository = WorldRepository(dataSource),
            worldKeyVerifier = WorldKeyVerifier(),
            accountRepository = AccountRepository(dataSource),
            passwordHasher = PasswordHasher(),
            sessionRepository = WorldSessionRepository(dataSource),
            worldListCache = null,
            punishmentService = PunishmentService(dataSource),
            worldOperationRepository = WorldOperationRepository(dataSource),
            worldLoginGateRepository = WorldLoginGateRepository(dataSource),
            badWordRoots = { badRoots },
        )

    private data class ParsedLoginOk(
        val token: ByteArray,
        val accountId: Long,
        val rights: String,
    )

    private fun readLoginOk(payload: ByteArray): ParsedLoginOk {
        return DataInputStream(ByteArrayInputStream(payload)).use { din ->
            assertEquals(WorldServerOpcodes.OP_LOGIN_OK, din.readUnsignedByte())
            val tokenLen = din.readUnsignedShort()
            assertEquals(WorldServerOpcodes.TOKEN_BYTES, tokenLen)
            val token = ByteArray(tokenLen)
            din.readFully(token)
            val accountId = din.readLong()
            val rights =
                if (din.available() >= 2) {
                    val rightsLen = din.readUnsignedShort()
                    val rightsBytes = ByteArray(rightsLen)
                    din.readFully(rightsBytes)
                    String(rightsBytes, StandardCharsets.UTF_8)
                } else {
                    ""
                }
            ParsedLoginOk(token, accountId, rights)
        }
    }

    private fun buildHelloFrame(
        magic: Int,
        worldId: Int,
        key: String,
        clientProtocolVersion: Int = WorldServerOpcodes.PROTOCOL_VERSION,
    ): ByteArray {
        val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeByte(WorldServerOpcodes.OP_WORLD_HELLO)
        d.writeInt(magic)
        d.writeShort(clientProtocolVersion)
        d.writeInt(worldId)
        d.writeShort(keyBytes.size)
        d.write(keyBytes)
        d.flush()
        return out.toByteArray()
    }

    private fun buildLoginFrame(
        user: String,
        pass: String,
        loginCharacterId: Int? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeByte(WorldServerOpcodes.OP_LOGIN)
        writeUtf8Len(d, user)
        writeUtf8Len(d, pass)
        if (loginCharacterId != null) {
            d.writeInt(loginCharacterId)
        }
        d.flush()
        return out.toByteArray()
    }

    private fun buildHeartbeatFrame(token: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeByte(WorldServerOpcodes.OP_HEARTBEAT)
        d.writeShort(token.size)
        d.write(token)
        d.flush()
        return out.toByteArray()
    }

    private fun buildLogoutFrame(token: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeByte(WorldServerOpcodes.OP_LOGOUT)
        d.writeShort(token.size)
        d.write(token)
        d.flush()
        return out.toByteArray()
    }

    private fun writeUtf8Len(
        d: DataOutputStream,
        s: String,
    ) {
        val b = s.toByteArray(StandardCharsets.UTF_8)
        d.writeShort(b.size)
        d.write(b)
    }
}
