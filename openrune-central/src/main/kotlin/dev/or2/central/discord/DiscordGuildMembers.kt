package dev.or2.central.discord

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent
import net.dv8tion.jda.api.events.user.update.UserUpdateGlobalNameEvent
import net.dv8tion.jda.api.events.user.update.UserUpdateNameEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.LoggerFactory

class DiscordGuildMembers(
    private val guildId: Long,
) : ListenerAdapter() {
    private val log = LoggerFactory.getLogger(DiscordGuildMembers::class.java)
    private val aliases = ConcurrentHashMap<String, Long>()
    private val keysByUserId = ConcurrentHashMap<Long, MutableSet<String>>()
    private val fullPreloadStarted = AtomicBoolean(false)
    private var jda: JDA? = null

    fun bind(jda: JDA) {
        this.jda = jda
    }

    fun seedCachedMembers(jda: JDA): Int {
        val guild =
            resolveGuild(jda) ?: run {
                log.warn("Discord member seed skipped: guild {} not found", guildId)
                return 0
            }
        guild.members.forEach(::register)
        val count = trackedMemberCount()
        log.info("Discord member directory seeded {} members from gateway cache (guild={})", count, guildId)
        return count
    }

    fun preloadAllMembersAsync(jda: JDA) {
        if (!fullPreloadStarted.compareAndSet(false, true)) {
            return
        }
        val guild = resolveGuild(jda) ?: return
        Thread(
            {
                try {
                    val members = guild.loadMembers().get()
                    members.forEach(::register)
                    log.info(
                        "Discord member directory full preload finished ({} members, guild={})",
                        trackedMemberCount(),
                        guildId,
                    )
                } catch (error: Exception) {
                    log.warn(
                        "Discord member full preload failed: {} (directory has {} members, guild={})",
                        error.message,
                        trackedMemberCount(),
                        guildId,
                    )
                }
            },
            "discord-member-preload",
        ).apply { isDaemon = true }.start()
    }

    fun findUserIdByUsername(discordUsername: String): Long? {
        aliases[normalize(discordUsername)]?.let { return it }

        val bot = jda ?: return null
        val guild = resolveGuild(bot) ?: return null
        val query = discordUsername.removePrefix("@").trim()
        if (query.isBlank()) {
            return null
        }

        guild.members.firstOrNull { matches(it, query) }?.idLong?.let { return it }
        return searchMembersByPrefix(guild, query)
    }

    override fun onGuildMemberJoin(event: GuildMemberJoinEvent) {
        if (!matchesGuild(event.guild.idLong)) {
            return
        }
        register(event.member)
    }

    override fun onGuildMemberRemove(event: GuildMemberRemoveEvent) {
        if (!matchesGuild(event.guild.idLong)) {
            return
        }
        unregister(event.user.idLong)
    }

    override fun onGuildMemberUpdateNickname(event: GuildMemberUpdateNicknameEvent) {
        if (!matchesGuild(event.guild.idLong)) {
            return
        }
        register(event.member)
    }

    override fun onUserUpdateName(event: UserUpdateNameEvent) {
        refreshMemberAliases(event.jda, event.user.idLong)
    }

    override fun onUserUpdateGlobalName(event: UserUpdateGlobalNameEvent) {
        refreshMemberAliases(event.jda, event.user.idLong)
    }

    private fun register(member: Member) {
        unregister(member.idLong)
        val keys = mutableSetOf<String>()
        fun addAlias(raw: String?) {
            val normalized = normalize(raw) ?: return
            keys.add(normalized)
            aliases[normalized] = member.idLong
        }
        addAlias(member.user.name)
        addAlias(member.user.globalName)
        addAlias(member.effectiveName)
        keysByUserId[member.idLong] = keys
    }

    private fun unregister(userId: Long) {
        keysByUserId.remove(userId)?.forEach { aliases.remove(it) }
    }

    private fun trackedMemberCount(): Int = keysByUserId.size

    private fun refreshMemberAliases(jda: JDA, userId: Long) {
        val guild = resolveGuild(jda) ?: return
        val member = guild.getMemberById(userId) ?: return
        register(member)
    }

    private fun searchMembersByPrefix(guild: Guild, query: String): Long? {
        if (query.length < 2) {
            return null
        }
        return try {
            guild
                .retrieveMembersByPrefix(query, 100)
                .get()
                .firstOrNull { matches(it, query) }
                ?.idLong
        } catch (_: Exception) {
            null
        }
    }

    private fun matches(member: Member, query: String): Boolean =
        member.user.name.equals(query, ignoreCase = true) ||
            member.user.globalName?.equals(query, ignoreCase = true) == true ||
            member.effectiveName.equals(query, ignoreCase = true)

    private fun matchesGuild(eventGuildId: Long): Boolean = guildId == eventGuildId

    private fun resolveGuild(jda: JDA): Guild? = jda.getGuildById(guildId)

    private fun normalize(raw: String?): String? =
        raw
            ?.removePrefix("@")
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
}
