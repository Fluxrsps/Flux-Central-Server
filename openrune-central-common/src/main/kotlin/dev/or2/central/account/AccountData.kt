package dev.or2.central.account

import dev.or2.roles.DonatorRanks
import dev.or2.roles.GameModes
import dev.or2.roles.Rights
import java.time.LocalDateTime

public data class CharacterData(
    public val characterId: Int,
    public val displayName: String?,
    public val previousDisplayName: String?,
    public val displayNameChangedAtMillis: Long?,
    public val members: Boolean,
    public val donatorRank: DonatorRanks = DonatorRanks.NONE,
    public val gameMode: GameModes = GameModes.ADVENTURER,
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

public data class TwoFactorAuthData(
    public val twoFactorSecret: String? = null,
    public val twoFactorRecoveryCodes: String? = null,
    public val twoFactorConfirmedAt: LocalDateTime? = null,
) {

    public val twoFactorConfirmed: Boolean
        get() = !twoFactorSecret.isNullOrBlank() && twoFactorConfirmedAt != null
}

public class AccountData(
    public val accountId: Int,
    public val accountName: String,
    public val rights: Rights,
    public val discordId: Long? = null,
    public val twoFactorAuth: TwoFactorAuthData = TwoFactorAuthData(),
    public val characterData: CharacterData,
) {

    override fun toString(): String =
        "AccountData(" +
            "accountId=$accountId, accountName=$accountName, discordId=$discordId, " +
            "twoFactorConfirmed=${twoFactorAuth.twoFactorConfirmed}, " +
            "characterId=${characterData.characterId}, displayName=${characterData.displayName}, " +
            "members=${characterData.members}, worldId=${characterData.worldId})"
}
