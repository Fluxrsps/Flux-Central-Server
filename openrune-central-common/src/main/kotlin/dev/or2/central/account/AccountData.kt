package dev.or2.central.account

import java.time.LocalDateTime
import java.time.ZoneId

public data class CharacterData(
    public val characterId: Int,
    public val displayName: String?,
    public val previousDisplayName: String?,
    public val displayNameChangedAtMillis: Long?,
    public val members: Boolean,
    public val modLevel: String?,
    public val worldId: Int?,
    public val coordX: Int,
    public val coordZ: Int,
    public val coordLevel: Int,
    public val varps: Map<Int, Int>,
    public val createdAt: LocalDateTime?,
    public val lastLogin: LocalDateTime?,
    public val lastLogout: LocalDateTime?,
    public val mutedUntil: LocalDateTime?,
    public val bannedUntil: LocalDateTime?,
    public val runEnergy: Int,
    public val xpRate: Double,
    public val attrs: Map<String, Any>,
    public val onlineCentralWorldId: Int? = null,
    public val onlineSessionHeartbeat: LocalDateTime? = null,
)

/**
 * Account row fields plus the loaded [account_characters] row ([characterData]).
 */
public class AccountData(
    public val accountId: Int,
    public val accountName: String,
    public val rights: String,
    public val email: String?,
    public val twofaEnabled: Boolean,
    public val twofaSecret: String?,
    public val twofaLastVerified: LocalDateTime?,
    public val knownDevice: Int?,
    public val characterData: CharacterData,
) {
    public val twofaLastVerifiedMillis: Long?
        get() =
            twofaLastVerified?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()

    override fun toString(): String =
        "AccountData(" +
            "accountId=$accountId, accountName=$accountName, " +
            "characterId=${characterData.characterId}, displayName=${characterData.displayName}, " +
            "members=${characterData.members}, worldId=${characterData.worldId})"
}
