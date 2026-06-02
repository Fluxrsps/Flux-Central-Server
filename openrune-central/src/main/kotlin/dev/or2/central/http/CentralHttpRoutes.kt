package dev.or2.central.http

import dev.or2.central.account.AccountNameAuthPolicy
import dev.or2.central.account.BadWordIndex
import dev.or2.central.server.logging.CentralActivityLogRepository
import dev.or2.central.server.session.WorldSessionRepository
import dev.or2.central.http.javconfig.JavConfigCache
import dev.or2.central.http.world.WorldListCache
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

data class CentralHttpContext(
    val sessionRepository: WorldSessionRepository,
    val activityLogRepository: CentralActivityLogRepository,
    val worldListCache: WorldListCache,
    val javConfigCache: JavConfigCache,
    val badWordIndex: BadWordIndex,
)

fun Route.centralHttpRoutes(ctx: CentralHttpContext) {
    singlePageApplication {
        applicationRoute = "/admin"
        useResources = true
        filesPath = "static/admin"
    }

    get("/worldslist.ws") {
        val bytes = ctx.worldListCache.snapshot()
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondBytes(bytes, ContentType.Application.OctetStream)
    }

    get("/worlds.js") {
        val body = ctx.worldListCache.worldListJsSnapshot()
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondText(body, ContentType.Application.Json)
    }

    get("/jav_config.ws") {
        val body =
            ctx.javConfigCache.snapshot()
                ?: run {
                    call.respondText(
                        "jav_config unavailable",
                        ContentType.Text.Plain,
                        HttpStatusCode.ServiceUnavailable,
                    )
                    return@get
                }
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondText(body, ContentType.Text.Plain)
    }

    get("/admin/api/account-name-deceptive-fragments.txt") {
        val lines = AccountNameAuthPolicy.deceptiveFragmentsForListing().sorted().joinToString("\n")
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondText(lines, ContentType.Text.Plain)
    }

    get("/admin/api/account-name-deceptive-fragments.json") {
        val frags = AccountNameAuthPolicy.deceptiveFragmentsForListing().sorted()
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respond(
            AccountNameDeceptiveFragmentsResponse(fragments = frags, count = frags.size),
        )
    }

    get("/admin/api/account-name-bad-words.txt") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondText(ctx.badWordIndex.mergedLinesText(), ContentType.Text.Plain)
    }

    get("/admin/api/account-name-bad-words.json") {
        val phrases = ctx.badWordIndex.roots().sorted()
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respond(
            AccountNameBadWordsResponse(
                phrases = phrases,
                count = phrases.size,
                maxCanonicalLength = AccountNameAuthPolicy.MAX_CANONICAL_LENGTH,
            ),
        )
    }

}
