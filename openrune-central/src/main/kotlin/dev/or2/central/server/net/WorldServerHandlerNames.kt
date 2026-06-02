package dev.or2.central.server.net

/**
 * Stable pipeline names for world-server channels (mirrors the idea of named stages in rsprot).
 */
internal object WorldServerHandlerNames {
    const val CONNECTION_GATE = "world-conn-gate"
    const val READ_TIMEOUT = "world-read-timeout"
    const val LENGTH_FRAME_DECODER = "world-length-frame-decoder"
    const val LENGTH_FRAME_ENCODER = "world-length-frame-encoder"
    const val INBOUND_RATE_LIMIT = "world-inbound-rate-limit"
    const val INBOUND_PACKET_DECODER = "world-inbound-packet-decoder"
    const val SESSION = "world-session"
}
