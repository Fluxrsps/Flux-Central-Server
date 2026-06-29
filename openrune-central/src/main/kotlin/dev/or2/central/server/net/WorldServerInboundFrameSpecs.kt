package dev.or2.central.server.net.codec

import dev.or2.central.server.net.protocol.WorldServerOpcodes

object WorldServerInboundFrameSpecs {

    // ===== TCP length-field chunk (after 4-byte big-endian length prefix is stripped) =====
    const val MAX_INBOUND_FRAMED_BODY: Int = 16 * 1024

    // ===== Field limits =====
    const val WORLD_KEY_MAX_BYTES: Int = 4096
    const val LOGIN_USERNAME_MAX_UTF8: Int = 64 * 4
    const val LOGIN_PASSWORD_MAX_UTF8: Int = 256 * 4
    const val TOKEN_BYTES: Int = WorldServerOpcodes.TOKEN_BYTES

    private const val TOKEN_BODY_BYTES: Int = 2 + TOKEN_BYTES

    private const val SOCIAL_NAME_MAX_UTF8: Int = 96
    private const val PRIVATE_MESSAGE_MAX_CHARS: Int = 255
    private const val PM_RELAY_SENDER_DISPLAY_MAX_UTF8: Int = 96
    private const val PM_RELAY_MESSAGE_MAX_UTF8: Int = PRIVATE_MESSAGE_MAX_CHARS * 4

    private const val SOCIAL_SYNC_BODY_BYTES: Int = TOKEN_BODY_BYTES + 4

    private const val SOCIAL_NAME_ACTION_MIN_BODY: Int = TOKEN_BODY_BYTES + 4 + 2 + 0
    private const val SOCIAL_NAME_ACTION_MAX_BODY: Int = TOKEN_BODY_BYTES + 4 + 2 + SOCIAL_NAME_MAX_UTF8

    private const val CHAT_FILTERS_BODY_BYTES: Int = TOKEN_BODY_BYTES + 4 + 3

    private const val PM_RELAY_MIN_BODY: Int = TOKEN_BODY_BYTES + 4 + 1 + 2 + 0 + 2 + 0 + 2 + 0

    private const val PM_RELAY_MAX_BODY: Int =
        TOKEN_BODY_BYTES +
                4 +
                1 +
                2 + SOCIAL_NAME_MAX_UTF8 +
                2 + PM_RELAY_SENDER_DISPLAY_MAX_UTF8 +
                2 + PM_RELAY_MESSAGE_MAX_UTF8

    // ===== Helper =====
    data class InboundSpec(
        val opcode: Int,
        val name: String,
        val minBody: Int,
        val maxBody: Int,
    )

    private val inboundSpecs: Map<Int, InboundSpec> = listOf(

        InboundSpec(
            opcode = WorldServerOpcodes.OP_WORLD_HELLO,
            name = "WORLD_HELLO",
            minBody = 4 + 2 + 4 + 2,
            maxBody = 4 + 2 + 4 + 2 + WORLD_KEY_MAX_BYTES
        ),

        InboundSpec(
            opcode = WorldServerOpcodes.OP_LOGIN,
            name = "LOGIN",
            minBody = 2 + 0 + 2 + 0,
            maxBody = 2 + LOGIN_USERNAME_MAX_UTF8 + 2 + LOGIN_PASSWORD_MAX_UTF8 + 4
        ),

        InboundSpec(
            opcode = WorldServerOpcodes.OP_PUSH_SUBSCRIBE,
            name = "PUSH_SUBSCRIBE",
            minBody = 0,
            maxBody = 0
        ),

        InboundSpec(
            opcode = WorldServerOpcodes.OP_HEARTBEAT,
            name = "HEARTBEAT",
            minBody = 2 + TOKEN_BYTES,
            maxBody = 2 + TOKEN_BYTES
        ),

        InboundSpec(
            opcode = WorldServerOpcodes.OP_LOGOUT,
            name = "LOGOUT",
            minBody = 2 + TOKEN_BYTES,
            maxBody = 2 + TOKEN_BYTES
        ),
        InboundSpec(
            opcode = WorldServerOpcodes.OP_WORLD_PM_RELAY,
            name = "WORLD_PM_RELAY",
            minBody = PM_RELAY_MIN_BODY,
            maxBody = PM_RELAY_MAX_BODY,
        ),

        InboundSpec(
            opcode = WorldServerOpcodes.OP_WORLD_FRIEND_ADD,
            name = "WORLD_FRIEND_ADD",
            minBody = SOCIAL_NAME_ACTION_MIN_BODY,
            maxBody = SOCIAL_NAME_ACTION_MAX_BODY,
        ),

        InboundSpec(
            opcode = WorldServerOpcodes.OP_WORLD_FRIEND_DEL,
            name = "WORLD_FRIEND_DEL",
            minBody = SOCIAL_NAME_ACTION_MIN_BODY,
            maxBody = SOCIAL_NAME_ACTION_MAX_BODY,
        ),

        InboundSpec(
            opcode = WorldServerOpcodes.OP_WORLD_IGNORE_ADD,
            name = "WORLD_IGNORE_ADD",
            minBody = SOCIAL_NAME_ACTION_MIN_BODY,
            maxBody = SOCIAL_NAME_ACTION_MAX_BODY,
        ),

        InboundSpec(
            opcode = WorldServerOpcodes.OP_WORLD_IGNORE_DEL,
            name = "WORLD_IGNORE_DEL",
            minBody = SOCIAL_NAME_ACTION_MIN_BODY,
            maxBody = SOCIAL_NAME_ACTION_MAX_BODY,
        ),

        InboundSpec(
            opcode = WorldServerOpcodes.OP_WORLD_CHAT_FILTERS,
            name = "WORLD_CHAT_FILTERS",
            minBody = CHAT_FILTERS_BODY_BYTES,
            maxBody = CHAT_FILTERS_BODY_BYTES,
        ),

        InboundSpec(
            opcode = WorldServerOpcodes.OP_WORLD_SOCIAL_SYNC,
            name = "WORLD_SOCIAL_SYNC",
            minBody = SOCIAL_SYNC_BODY_BYTES,
            maxBody = SOCIAL_SYNC_BODY_BYTES,
        ),

        InboundSpec(
            opcode = WorldServerOpcodes.OP_GAME_DISCORD_LINK_PENDING,
            name = "GAME_DISCORD_LINK_PENDING",
            minBody = 4 + 2 + 1,
            maxBody = 4 + 2 + WorldServerOpcodes.GAME_DISCORD_LINK_USERNAME_MAX_UTF8,
        ),

        InboundSpec(
            opcode = WorldServerOpcodes.OP_GAME_DISCORD_LINK_INVALIDATE,
            name = "GAME_DISCORD_LINK_INVALIDATE",
            minBody = 4,
            maxBody = 4,
        ),

        InboundSpec(
            opcode = WorldServerOpcodes.OP_GAME_ANNOUNCEMENT,
            name = "GAME_ANNOUNCEMENT",
            minBody = 2 + 1,
            maxBody = 2 + WorldServerOpcodes.GAME_ANNOUNCEMENT_JSON_MAX_UTF8,
        ),

        ).associateBy { it.opcode }

    fun spec(opcode: Int): InboundSpec? = inboundSpecs[opcode]

    fun validateInboundBody(
        opcode: Int,
        bodyBytes: Int
    ): String? {
        val spec = inboundSpecs[opcode] ?: return "unknown_opcode"

        if (bodyBytes < spec.minBody) return "too_short"
        if (bodyBytes > spec.maxBody) return "too_long"

        return null
    }

    // ===== Derived helpers (so you NEVER hardcode elsewhere) =====

    fun loginFailMaxBody(): Int {
        return 4 + 3 * (2 + WorldServerOpcodes.LOGIN_FAIL_SCRIPT_LINE_MAX_UTF8_BYTES)
    }

    fun loginOkMaxBody(clientProtocolVersion: Int): Int {
        val base = 2 + WorldServerOpcodes.TOKEN_BYTES + 8
        return if (clientProtocolVersion >= 3) {
            base + 2 + WorldServerOpcodes.LOGIN_OK_RIGHTS_MAX_BYTES
        } else base
    }
}