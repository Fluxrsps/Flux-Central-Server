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

    /** World-link push: account character display_name changed in Central DB (see NOTIFY `character_display_name_events`). */
    const val OP_SERVER_DISPLAY_NAME_SYNC: Int = 0x57

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

    /**
     * Reserved numeric code for account / display-name policy (profanity, deceptive fragments, format).
     * Central currently sends `LOGIN_FAIL_WORLD_ACCESS` (12) with the policy script trailer on the wire so
     * game clients that only show v5 script dialogs for world-gate codes still display the policy lines.
     */
    const val LOGIN_FAIL_ACCOUNT_NAME: Int = 16

    const val TOKEN_BYTES: Int = 32
}
