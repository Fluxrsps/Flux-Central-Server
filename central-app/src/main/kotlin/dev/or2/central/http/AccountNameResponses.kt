package dev.or2.central.http

import kotlinx.serialization.Serializable

@Serializable
data class AccountNameDeceptiveFragmentsResponse(
    val fragments: List<String>,
    val count: Int,
)

@Serializable
data class AccountNameBadWordsResponse(
    val phrases: List<String>,
    val count: Int,
    val maxCanonicalLength: Int,
)
