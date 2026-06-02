package dev.or2.central.http

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class InfoResponse(val name: String, val version: String)

@Serializable
data class WorldOnlineDto(val worldId: Int, val players: Int)

@Serializable
data class PublicStatsResponse(
    val onlineTotal: Int,
    val worlds: List<WorldOnlineDto>,
    val distinctLoginsTodayUtc: Int,
)

/** Account-name deceptive / staff-style substring fragments (world-link); separate from profanity list. */
@Serializable
data class AccountNameDeceptiveFragmentsResponse(
    val fragments: List<String>,
    val count: Int,
)

@Serializable
data class AccountNameBadWordsResponse(
    val phrases: List<String>,
    val count: Int,
    /** Same as [dev.or2.central.account.AccountNameAuthPolicy.MAX_CANONICAL_LENGTH]. */
    val maxCanonicalLength: Int,
)
