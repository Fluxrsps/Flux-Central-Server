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