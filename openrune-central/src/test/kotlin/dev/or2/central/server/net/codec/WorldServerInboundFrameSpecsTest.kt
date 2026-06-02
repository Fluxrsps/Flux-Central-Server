package dev.or2.central.server.net.codec

import dev.or2.central.server.net.protocol.WorldServerOpcodes
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class WorldServerInboundFrameSpecsTest {
    @Test
    fun inboundHelloBounds() {
        assertNull(WorldServerInboundFrameSpecs.validateInboundBody(WorldServerOpcodes.OP_WORLD_HELLO, 12))
        assertEquals(
            "too_short",
            WorldServerInboundFrameSpecs.validateInboundBody(WorldServerOpcodes.OP_WORLD_HELLO, 11),
        )
        assertEquals(
            "too_long",
            WorldServerInboundFrameSpecs.validateInboundBody(
                WorldServerOpcodes.OP_WORLD_HELLO,
                4 + 2 + 4 + 2 + WorldServerInboundFrameSpecs.WORLD_KEY_MAX_BYTES + 1,
            ),
        )
    }

    @Test
    fun inboundHeartbeatExactLength() {
        val bodyLen = 2 + WorldServerInboundFrameSpecs.TOKEN_BYTES
        assertNull(
            WorldServerInboundFrameSpecs.validateInboundBody(
                WorldServerOpcodes.OP_HEARTBEAT,
                bodyLen,
            ),
        )
        assertEquals(
            "too_short",
            WorldServerInboundFrameSpecs.validateInboundBody(WorldServerOpcodes.OP_HEARTBEAT, bodyLen - 1),
        )
        assertEquals(
            "too_long",
            WorldServerInboundFrameSpecs.validateInboundBody(WorldServerOpcodes.OP_HEARTBEAT, bodyLen + 1),
        )
    }

    @Test
    fun unknownOpcode() {
        assertEquals("unknown_opcode", WorldServerInboundFrameSpecs.validateInboundBody(0x99, 0))
    }

    @Test
    fun inboundLoginAllowsOptionalCharacterIdTrailer() {
        val max =
            2 + WorldServerInboundFrameSpecs.LOGIN_USERNAME_MAX_UTF8 +
                2 + WorldServerInboundFrameSpecs.LOGIN_PASSWORD_MAX_UTF8 + 4
        assertNull(WorldServerInboundFrameSpecs.validateInboundBody(WorldServerOpcodes.OP_LOGIN, max))
        assertEquals(
            "too_long",
            WorldServerInboundFrameSpecs.validateInboundBody(WorldServerOpcodes.OP_LOGIN, max + 1),
        )
    }
}
