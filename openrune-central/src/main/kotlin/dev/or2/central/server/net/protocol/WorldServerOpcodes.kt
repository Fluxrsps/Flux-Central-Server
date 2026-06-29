package dev.or2.central.server.net.protocol

object WorldServerOpcodes {
    const val MAGIC: Int = 0x4F523231 // OR21

    const val OP_WORLD_HELLO: Int = 0x01
    const val OP_HELLO_ACK: Int = 0x02
    const val OP_HELLO_REJECT: Int = 0x03

    const val OP_LOGIN: Int = 0x10
    const val OP_LOGIN_OK: Int = 0x11
    const val OP_LOGIN_FAIL: Int = 0x12

    const val OP_PUSH_SUBSCRIBE: Int = 0x13
    const val OP_PUSH_SUBSCRIBE_ACK: Int = 0x14

    const val OP_HEARTBEAT: Int = 0x30
    const val OP_HEARTBEAT_ACK: Int = 0x31

    const val OP_LOGOUT: Int = 0x40
    const val OP_LOGOUT_ACK: Int = 0x41

    const val OP_SERVER_REVOKE_LOGIN: Int = 0x50
    const val OP_SERVER_MUTE_UPDATE: Int = 0x51
    const val OP_SERVER_KICK: Int = 0x52
    const val OP_SERVER_REBOOT: Int = 0x54
    const val OP_SERVER_BROADCAST: Int = 0x55

    /** World-link push: account discord_id changed in Central DB (see NOTIFY `account_discord_id_events`). */
    const val OP_SERVER_DISCORD_ID_SYNC: Int = 0x56
    const val OP_WORLD_PM_RELAY = 0x60
    const val OP_WORLD_FRIEND_ADD = 0x61
    const val OP_WORLD_FRIEND_DEL = 0x62
    const val OP_WORLD_IGNORE_ADD = 0x63
    const val OP_WORLD_IGNORE_DEL = 0x64
    const val OP_WORLD_CHAT_FILTERS = 0x65

    const val OP_WORLD_SOCIAL_OK = 0x66
    const val OP_WORLD_SOCIAL_FAIL = 0x67

    const val OP_WORLD_SOCIAL_SYNC = 0x69
    const val OP_WORLD_SOCIAL_SYNC_OK = 0x6A
    const val OP_WORLD_SOCIAL_SYNC_FAIL = 0x6B

    const val OP_SERVER_PRIVATE_MESSAGE = 0x68
    const val OP_SERVER_FRIEND_PRESENCE = 0x6C

    /** World-link push: account character display_name changed in Central DB (see NOTIFY `character_display_name_events`). */
    const val OP_SERVER_DISPLAY_NAME_SYNC: Int = 0x57

    /** World → Central: start in-game Discord code verification (`::discordlink`). */
    const val OP_GAME_DISCORD_LINK_PENDING: Int = 0x58

    const val OP_GAME_DISCORD_LINK_PENDING_OK: Int = 0x59

    const val OP_GAME_DISCORD_LINK_PENDING_FAIL: Int = 0x5A

    /** World → Central: clear pending Discord link state (e.g. on logout). */
    const val OP_GAME_DISCORD_LINK_INVALIDATE: Int = 0x5B

    const val OP_GAME_DISCORD_LINK_INVALIDATE_ACK: Int = 0x5C

    /** World → Central: relay a server-wide announcement to all worlds + Discord. */
    const val OP_GAME_ANNOUNCEMENT: Int = 0x5D

    const val OP_GAME_ANNOUNCEMENT_ACK: Int = 0x5E

    const val GAME_ANNOUNCEMENT_JSON_MAX_UTF8: Int = 8192

    const val GAME_DISCORD_LINK_PENDING_FAIL_BAD_FRAME: Int = 1

    const val GAME_DISCORD_LINK_PENDING_FAIL_ALREADY_LINKED: Int = 2

    const val GAME_DISCORD_LINK_PENDING_FAIL_DISCORD_NOT_FOUND: Int = 3

    const val GAME_DISCORD_LINK_PENDING_FAIL_UNAVAILABLE: Int = 4

    const val GAME_DISCORD_LINK_USERNAME_MAX_UTF8: Int = 128

    /** World-link version used in tests; supported client range must cover the game server's WORLD_HELLO u16. */
    const val PROTOCOL_VERSION: Int = 7

    const val MIN_CLIENT_PROTOCOL_VERSION: Int = 2

    const val MAX_CLIENT_PROTOCOL_VERSION: Int = 7

    const val LOGIN_OK_RIGHTS_MAX_BYTES: Int = 4096

    /** Max UTF-8 bytes per line on [OP_LOGIN_FAIL] script trailer (protocol v5+). */
    const val LOGIN_FAIL_SCRIPT_LINE_MAX_UTF8_BYTES: Int = 512

    const val HELLO_REASON_PROTOCOL: Int = 1
    const val HELLO_REASON_BAD_KEY: Int = 2
    const val HELLO_REASON_UNKNOWN_WORLD: Int = 3
    const val HELLO_REASON_WORLD_DISABLED: Int = 4

    /**
     * World hello used the optional web-protocol trailer and it disagreed with this link's protocol
     * or with [PROTOCOL_VERSION] from `/worlds.js`. Game clients should prompt to restart / update
     * so lobby (world-link) and web list stay aligned.
     */
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

    const val SOCIAL_FAIL_USER_NOT_FOUND = 1
    const val SOCIAL_FAIL_SELF_ACTION = 2
    const val SOCIAL_FAIL_ALREADY_FRIEND = 3
    const val SOCIAL_FAIL_ALREADY_IGNORED = 4
    const val SOCIAL_FAIL_NOT_ACCEPTING_PRIVATE = 5
    const val SOCIAL_FAIL_NOT_LOGGED_IN = 6
    const val SOCIAL_FAIL_LIST_FULL = 7
    const val SOCIAL_FAIL_NOT_ALLOWED = 8

    /**
     * Reserved numeric code for account / display-name policy (profanity, deceptive fragments, format).
     * Central currently sends `LOGIN_FAIL_WORLD_ACCESS` (12) with the policy script trailer on the wire so
     * game clients that only show v5 script dialogs for world-gate codes still display the policy lines.
     */
    const val LOGIN_FAIL_ACCOUNT_NAME: Int = 16

    const val TOKEN_BYTES: Int = 32
}
