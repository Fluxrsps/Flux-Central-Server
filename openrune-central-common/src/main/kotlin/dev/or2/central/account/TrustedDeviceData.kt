package dev.or2.central.account

import java.time.LocalDateTime

public data class TrustedDeviceData(
    public val deviceId: Int,
    public val verifiedAt: LocalDateTime,
)
