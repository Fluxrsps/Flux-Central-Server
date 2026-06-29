package dev.or2.central.social

import javax.sql.DataSource

data class SocialCharacterRow(
    val characterId: Int,
    val accountId: Long,
    val displayName: String,
)

data class SocialSessionRow(
    val accountId: Long,
    val worldId: Int,
    val characterId: Int,
)

data class ChatFiltersRow(
    val publicChat: Int,
    val privateChat: Int,
    val tradeChat: Int,
)

data class SocialFriendSnapshotRow(
    val displayName: String,
    val previousDisplayName: String?,
    val worldId: Int,
)

data class SocialIgnoreSnapshotRow(
    val displayName: String,
    val previousDisplayName: String?,
)

data class CentralSocialSnapshotRow(
    val filters: ChatFiltersRow,
    val friends: List<SocialFriendSnapshotRow>,
    val ignores: List<SocialIgnoreSnapshotRow>,
)

data class FriendPresenceRecipientRow(
    val ownerCharacterId: Int,
    val ownerWorldId: Int,
    val friendCharacterId: Int,
    val friendDisplayName: String,
    val friendPreviousDisplayName: String?,
    val visibleWorldId: Int,
)

class CentralSocialRepository(
    private val dataSource: DataSource,
) {
    fun findCharacterByDisplayName(name: String): SocialCharacterRow? {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) {
            return null
        }

        val sql =
            """
            SELECT id, account_id, display_name
            FROM account_characters
            WHERE LOWER(display_name) = LOWER(?)
            LIMIT 1
            """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, cleaned)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return null
                    }

                    return SocialCharacterRow(
                        characterId = rs.getInt("id"),
                        accountId = rs.getLong("account_id"),
                        displayName = clientDisplayName(rs.getString("display_name") ?: cleaned),
                    )
                }
            }
        }
    }

    fun onlineSessionForCharacter(characterId: Int): SocialSessionRow? {
        val sql =
            """
            SELECT account_id, world_id, character_id
            FROM sessions
            WHERE character_id = ?
            ORDER BY last_seen_at DESC
            LIMIT 1
            """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, characterId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return null
                    }

                    val cid = rs.getObject("character_id") ?: return null

                    return SocialSessionRow(
                        accountId = rs.getLong("account_id"),
                        worldId = rs.getInt("world_id"),
                        characterId = (cid as Number).toInt(),
                    )
                }
            }
        }
    }

    fun addFriend(
        ownerCharacterId: Int,
        friendCharacterId: Int,
    ): Boolean {
        val sql =
            """
            INSERT INTO character_friends (owner_character_id, friend_character_id)
            VALUES (?, ?)
            ON CONFLICT DO NOTHING
            """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, ownerCharacterId)
                ps.setInt(2, friendCharacterId)
                return ps.executeUpdate() > 0
            }
        }
    }

    fun deleteFriend(
        ownerCharacterId: Int,
        friendCharacterId: Int,
    ) {
        val sql =
            """
            DELETE FROM character_friends
            WHERE owner_character_id = ? AND friend_character_id = ?
            """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, ownerCharacterId)
                ps.setInt(2, friendCharacterId)
                ps.executeUpdate()
            }
        }
    }

    fun isFriend(
        ownerCharacterId: Int,
        friendCharacterId: Int,
    ): Boolean {
        val sql =
            """
            SELECT 1
            FROM character_friends
            WHERE owner_character_id = ? AND friend_character_id = ?
            LIMIT 1
            """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, ownerCharacterId)
                ps.setInt(2, friendCharacterId)
                ps.executeQuery().use { rs ->
                    return rs.next()
                }
            }
        }
    }

    fun addIgnore(
        ownerCharacterId: Int,
        ignoredCharacterId: Int,
    ): Boolean {
        val sql =
            """
            INSERT INTO character_ignores (owner_character_id, ignored_character_id)
            VALUES (?, ?)
            ON CONFLICT DO NOTHING
            """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, ownerCharacterId)
                ps.setInt(2, ignoredCharacterId)
                return ps.executeUpdate() > 0
            }
        }
    }

    fun deleteIgnore(
        ownerCharacterId: Int,
        ignoredCharacterId: Int,
    ) {
        val sql =
            """
            DELETE FROM character_ignores
            WHERE owner_character_id = ? AND ignored_character_id = ?
            """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, ownerCharacterId)
                ps.setInt(2, ignoredCharacterId)
                ps.executeUpdate()
            }
        }
    }

    fun isIgnored(
        ownerCharacterId: Int,
        ignoredCharacterId: Int,
    ): Boolean {
        val sql =
            """
            SELECT 1
            FROM character_ignores
            WHERE owner_character_id = ? AND ignored_character_id = ?
            LIMIT 1
            """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, ownerCharacterId)
                ps.setInt(2, ignoredCharacterId)
                ps.executeQuery().use { rs ->
                    return rs.next()
                }
            }
        }
    }

    fun snapshot(characterId: Int): CentralSocialSnapshotRow {
        return CentralSocialSnapshotRow(
            filters = chatFilters(characterId),
            friends = listFriends(characterId),
            ignores = listIgnores(characterId),
        )
    }

    fun listFriends(ownerCharacterId: Int): List<SocialFriendSnapshotRow> {
        val sql =
            """
        SELECT
            c.display_name,
            c.previous_display_name,
            s.world_id,
            COALESCE(f.public_chat, 0) AS public_chat,
            COALESCE(f.private_chat, 0) AS private_chat,
            EXISTS (
        SELECT 1
            FROM character_ignores i
            WHERE i.owner_character_id = c.id
            AND i.ignored_character_id = ?
            ) AS target_ignores_owner,
            EXISTS (
        SELECT 1
            FROM character_ignores oi
            WHERE oi.owner_character_id = ?
            AND oi.ignored_character_id = c.id
            ) AS owner_ignores_target,
            EXISTS (
        SELECT 1
            FROM character_friends rf
            WHERE rf.owner_character_id = c.id
            AND rf.friend_character_id = ?
            ) AS target_has_owner_friend
            FROM character_friends cf
            JOIN account_characters c ON c.id = cf.friend_character_id
            LEFT JOIN sessions s ON s.character_id = c.id
            LEFT JOIN character_chat_filters f ON f.character_id = c.id
            WHERE cf.owner_character_id = ?
            ORDER BY LOWER(c.display_name)
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, ownerCharacterId) // target_ignores_owner
                ps.setInt(2, ownerCharacterId) // owner_ignores_target
                ps.setInt(3, ownerCharacterId) // target_has_owner_friend
                ps.setInt(4, ownerCharacterId) // WHERE cf.owner_character_id

                ps.executeQuery().use { rs ->
                    val out = mutableListOf<SocialFriendSnapshotRow>()

                    while (rs.next()) {
                        val display = clientDisplayName(rs.getString("display_name"))
                        if (display.isBlank()) {
                            continue
                        }

                        val previous = clientPreviousDisplayName(rs.getString("previous_display_name"))
                        val rawWorld = rs.getObject("world_id")?.let { (it as Number).toInt() } ?: 0
                        val privateChat = rs.getInt("private_chat")
                        val targetIgnoresOwner = rs.getBoolean("target_ignores_owner")
                        val ownerIgnoresTarget = rs.getBoolean("owner_ignores_target")
                        val targetHasOwnerFriend = rs.getBoolean("target_has_owner_friend")

                        val visibleOnline =
                            rawWorld > 0 &&
                                    !ownerIgnoresTarget &&
                                    !targetIgnoresOwner &&
                                    (
                                            privateChat == 0 ||
                                                    (privateChat == 1 && targetHasOwnerFriend)
                                            )

                        out +=
                            SocialFriendSnapshotRow(
                                displayName = display,
                                previousDisplayName = previous,
                                worldId = if (visibleOnline) rawWorld else 0,
                            )
                    }

                    return out
                }
            }
        }
    }

    fun listIgnores(ownerCharacterId: Int): List<SocialIgnoreSnapshotRow> {
        val sql =
            """
        SELECT c.display_name, c.previous_display_name
        FROM character_ignores ci
        JOIN account_characters c ON c.id = ci.ignored_character_id
        WHERE ci.owner_character_id = ?
        ORDER BY LOWER(c.display_name)
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, ownerCharacterId)

                ps.executeQuery().use { rs ->
                    val out = mutableListOf<SocialIgnoreSnapshotRow>()

                    while (rs.next()) {
                        val display = clientDisplayName(rs.getString("display_name"))
                        if (display.isBlank()) {
                            continue
                        }

                        out +=
                            SocialIgnoreSnapshotRow(
                                displayName = display,
                                previousDisplayName = clientPreviousDisplayName(rs.getString("previous_display_name")),
                            )
                    }

                    return out
                }
            }
        }
    }

    private fun clientDisplayName(raw: String?): String {
        // Display names are stored with client-facing casing. Preserve exact casing here;
        // this helper only trims database/wire noise.
        return raw?.trim().orEmpty()
    }

    private fun clientPreviousDisplayName(raw: String?): String? {
        return raw?.trim()?.takeIf { it.isNotBlank() }
    }

    fun friendPresenceRecipients(
        friendCharacterId: Int,
        onlineWorldId: Int,
    ): List<FriendPresenceRecipientRow> {
        val sql =
            """
        SELECT
            owner_s.character_id AS owner_character_id,
            owner_s.world_id AS owner_world_id,
            friend.id AS friend_character_id,
            friend.display_name AS friend_display_name,
            friend.previous_display_name AS friend_previous_display_name,
            COALESCE(filters.private_chat, 0) AS private_chat,
            EXISTS (
        SELECT 1
            FROM character_ignores i
            WHERE i.owner_character_id = friend.id
            AND i.ignored_character_id = cf.owner_character_id
            ) AS friend_ignores_owner,
            EXISTS (
        SELECT 1
            FROM character_ignores owner_ignore
            WHERE owner_ignore.owner_character_id = cf.owner_character_id
            AND owner_ignore.ignored_character_id = friend.id
            ) AS owner_ignores_friend,
            EXISTS (
        SELECT 1
            FROM character_friends reciprocal
            WHERE reciprocal.owner_character_id = friend.id
            AND reciprocal.friend_character_id = cf.owner_character_id
            ) AS friend_has_owner_added
            FROM character_friends cf
            JOIN account_characters friend ON friend.id = cf.friend_character_id
            JOIN sessions owner_s ON owner_s.character_id = cf.owner_character_id
            LEFT JOIN character_chat_filters filters ON filters.character_id = friend.id
            WHERE cf.friend_character_id = ?
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, friendCharacterId)

                ps.executeQuery().use { rs ->
                    val out = mutableListOf<FriendPresenceRecipientRow>()

                    while (rs.next()) {
                        val privateChat = rs.getInt("private_chat")
                        val friendIgnoresOwner = rs.getBoolean("friend_ignores_owner")
                        val ownerIgnoresFriend = rs.getBoolean("owner_ignores_friend")
                        val friendHasOwnerAdded = rs.getBoolean("friend_has_owner_added")
                        val display = clientDisplayName(rs.getString("friend_display_name"))

                        if (display.isBlank()) {
                            continue
                        }

                        val visibleOnline =
                            onlineWorldId > 0 &&
                                    !ownerIgnoresFriend &&
                                    !friendIgnoresOwner &&
                                    (
                                            privateChat == 0 ||
                                                    (privateChat == 1 && friendHasOwnerAdded)
                                            )

                        out +=
                            FriendPresenceRecipientRow(
                                ownerCharacterId = rs.getInt("owner_character_id"),
                                ownerWorldId = rs.getInt("owner_world_id"),
                                friendCharacterId = rs.getInt("friend_character_id"),
                                friendDisplayName = display,
                                friendPreviousDisplayName = clientPreviousDisplayName(
                                    rs.getString("friend_previous_display_name")
                                ),
                                visibleWorldId = if (visibleOnline) onlineWorldId else 0,
                            )
                    }

                    return out
                }
            }
        }
    }

    fun setPrivateChatFilter(
        characterId: Int,
        privateChat: Int,
    ) {
        val sql =
            """
        INSERT INTO character_chat_filters (character_id, private_chat)
        VALUES (?, ?)
        ON CONFLICT (character_id) DO UPDATE SET
            private_chat = EXCLUDED.private_chat
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, characterId)
                ps.setInt(2, privateChat.coerceIn(0, 2))
                ps.executeUpdate()
            }
        }
    }

    fun chatFilters(characterId: Int): ChatFiltersRow {
        val sql =
            """
            SELECT public_chat, private_chat, trade_chat
            FROM character_chat_filters
            WHERE character_id = ?
            """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, characterId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return ChatFiltersRow(
                            publicChat = 0,
                            privateChat = 0,
                            tradeChat = 0,
                        )
                    }

                    return ChatFiltersRow(
                        publicChat = rs.getInt("public_chat"),
                        privateChat = rs.getInt("private_chat"),
                        tradeChat = rs.getInt("trade_chat"),
                    )
                }
            }
        }
    }
}