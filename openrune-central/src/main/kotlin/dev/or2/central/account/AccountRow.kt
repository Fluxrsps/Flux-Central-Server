package dev.or2.central.account

data class AccountRow(
    val id: Long,
    val username: String,
    val passwordHash: String,
    val rights: String,
)
