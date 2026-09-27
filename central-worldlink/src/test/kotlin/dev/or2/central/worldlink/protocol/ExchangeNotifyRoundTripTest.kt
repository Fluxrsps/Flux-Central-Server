package dev.or2.central.worldlink.protocol

import dev.or2.central.worldlink.protocol.packets.outgoing.impl.ServerExchangeNotifyPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExchangeNotifyRoundTripTest {
    @Test
    fun encodesAndDecodes() {
        val payload = ServerExchangeNotifyPacket.Payload(42, 9001L, "ORDER_FILLED", 77L, 4151, """{"quantity":3,"unit_price":1500000}""")
        val frame = ServerExchangeNotifyPacket.encode(payload)
        assertEquals(WorldOpcodes.OP_SERVER_EXCHANGE_NOTIFY, frame[0].toInt() and 0xFF)
        assertNull(PacketCatalog.validateCentralToGameFrame(frame))
        val decoded = CentralPushPackets.decodeExchangeNotify(frame)
        assertEquals(payload.characterId, decoded.characterId)
        assertEquals(payload.notificationId, decoded.notificationId)
        assertEquals(payload.kind, decoded.kind)
        assertEquals(payload.orderId, decoded.orderId)
        assertEquals(payload.objId, decoded.objId)
        assertEquals(payload.payloadJson, decoded.payloadJson)
    }
}
