package dev.or2.central.worldlink.protocol

object WorldOpcodes {
    /** ASCII "OR21" — world-link magic in WORLD_HELLO. */
    const val MAGIC: Int = 0x4F523231

    const val OP_WORLD_HELLO: Int = 1
    const val OP_HELLO_ACK: Int = 2
    const val OP_HELLO_REJECT: Int = 3

    const val OP_LOGIN: Int = 16
    const val OP_LOGIN_OK: Int = 17
    const val OP_LOGIN_FAIL: Int = 18

    const val OP_PUSH_SUBSCRIBE: Int = 19
    const val OP_PUSH_SUBSCRIBE_ACK: Int = 20

    const val OP_HEARTBEAT: Int = 48
    const val OP_HEARTBEAT_ACK: Int = 49

    const val OP_LOGOUT: Int = 64
    const val OP_LOGOUT_ACK: Int = 65

    const val OP_SERVER_REVOKE_LOGIN: Int = 80
    const val OP_SERVER_MUTE_UPDATE: Int = 81
    const val OP_SERVER_KICK: Int = 82
    const val OP_SERVER_REBOOT: Int = 84
    const val OP_SERVER_BROADCAST: Int = 85
    const val OP_SERVER_DISCORD_ID_SYNC: Int = 86
    const val OP_SERVER_DISPLAY_NAME_SYNC: Int = 87

    const val OP_GAME_DISCORD_LINK_PENDING: Int = 88
    const val OP_GAME_DISCORD_LINK_PENDING_OK: Int = 89
    const val OP_GAME_DISCORD_LINK_PENDING_FAIL: Int = 90
    const val OP_GAME_DISCORD_LINK_INVALIDATE: Int = 91
    const val OP_GAME_DISCORD_LINK_INVALIDATE_ACK: Int = 92

    const val GAME_DISCORD_LINK_PENDING_FAIL_BAD_FRAME: Int = 1
    const val GAME_DISCORD_LINK_PENDING_FAIL_ALREADY_LINKED: Int = 2
    const val GAME_DISCORD_LINK_PENDING_FAIL_DISCORD_NOT_FOUND: Int = 3
    const val GAME_DISCORD_LINK_PENDING_FAIL_UNAVAILABLE: Int = 4
    const val GAME_DISCORD_LINK_USERNAME_MAX_UTF8: Int = 128

    const val OP_WORLD_PM_RELAY: Int = 96
    const val OP_WORLD_FRIEND_ADD: Int = 97
    const val OP_WORLD_FRIEND_DEL: Int = 98
    const val OP_WORLD_IGNORE_ADD: Int = 99
    const val OP_WORLD_IGNORE_DEL: Int = 100
    const val OP_WORLD_CHAT_FILTERS: Int = 101

    const val OP_WORLD_SOCIAL_OK: Int = 102
    const val OP_WORLD_SOCIAL_FAIL: Int = 103
    const val OP_SERVER_PRIVATE_MESSAGE: Int = 104
    const val OP_WORLD_SOCIAL_SYNC: Int = 105
    const val OP_WORLD_SOCIAL_SYNC_OK: Int = 106
    const val OP_WORLD_SOCIAL_SYNC_FAIL: Int = 107
    const val OP_SERVER_FRIEND_PRESENCE: Int = 108

    const val OP_WORLD_LAYOUT_SAVE: Int = 112
    const val OP_WORLD_LAYOUT_LOAD: Int = 113
    const val OP_WORLD_LAYOUT_LIST: Int = 114
    const val OP_WORLD_LAYOUT_SHARED: Int = 116

    const val OP_WORLD_LAYOUT_OK: Int = 117
    const val OP_WORLD_LAYOUT_FAIL: Int = 118
    const val OP_WORLD_LAYOUT_DATA: Int = 119
    const val OP_WORLD_LAYOUT_LIST_OK: Int = 120

    const val LAYOUT_FAIL_NOT_ALLOWED: Int = 1
    const val LAYOUT_FAIL_BAD_SLOT: Int = 2
    const val LAYOUT_FAIL_UNKNOWN_CHARACTER: Int = 3
    const val LAYOUT_FAIL_EMPTY: Int = 4
    const val LAYOUT_FAIL_NOT_SHARED: Int = 5
    const val LAYOUT_FAIL_STORAGE: Int = 6

    const val PROTOCOL_VERSION: Int = 8
    const val MIN_CLIENT_PROTOCOL_VERSION: Int = 2
    const val MAX_CLIENT_PROTOCOL_VERSION: Int = 8

    const val LOGIN_OK_RIGHTS_MAX_BYTES: Int = 4096
    const val LOGIN_FAIL_SCRIPT_LINE_MAX_UTF8_BYTES: Int = 512
    const val TOKEN_BYTES: Int = 32

    const val HELLO_REASON_PROTOCOL: Int = 1
    const val HELLO_REASON_BAD_KEY: Int = 2
    const val HELLO_REASON_UNKNOWN_WORLD: Int = 3
    const val HELLO_REASON_WORLD_DISABLED: Int = 4
    const val HELLO_REASON_RESTART_CLIENT: Int = 5

    const val LOGIN_FAIL_INVALID: Int = 1
    const val LOGIN_FAIL_WORLD_FULL: Int = 2
    const val LOGIN_FAIL_ALREADY_ONLINE: Int = 3
    const val LOGIN_FAIL_WORLD_DISABLED: Int = 4
    const val LOGIN_FAIL_NOT_HANDSHAKEN: Int = 5
    const val LOGIN_FAIL_BAD_FRAME: Int = 6
    const val LOGIN_FAIL_BAD_TOKEN: Int = 7
    const val LOGIN_FAIL_BANNED: Int = 8
    const val LOGIN_FAIL_MUTED: Int = 9
    const val LOGIN_FAIL_LOCKED: Int = 10
    const val LOGIN_FAIL_UPDATE_IN_PROGRESS: Int = 11
    const val LOGIN_FAIL_WORLD_ACCESS: Int = 12
    const val LOGIN_FAIL_WORLD_MIN_LEVEL: Int = 13
    const val LOGIN_FAIL_WORLD_WHITELIST: Int = 14
    const val LOGIN_FAIL_WORLD_RIGHTS: Int = 15
    const val LOGIN_FAIL_ACCOUNT_NAME: Int = 16

    const val SOCIAL_FAIL_USER_NOT_FOUND: Int = 1
    const val SOCIAL_FAIL_SELF_ACTION: Int = 2
    const val SOCIAL_FAIL_ALREADY_FRIEND: Int = 3
    const val SOCIAL_FAIL_ALREADY_IGNORED: Int = 4
    const val SOCIAL_FAIL_NOT_ACCEPTING_PRIVATE: Int = 5
    const val SOCIAL_FAIL_NOT_LOGGED_IN: Int = 6
    const val SOCIAL_FAIL_LIST_FULL: Int = 7
    const val SOCIAL_FAIL_NOT_ALLOWED: Int = 8
}
