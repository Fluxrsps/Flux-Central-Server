package dev.or2.central.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private fun io.ktor.server.application.ApplicationCall.allowBrowserRead() {
    response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
}

fun Route.centralHttpRoutes(
    worldListCache: WorldListCache,
    javConfigCache: JavConfigCache,
) {
    get("/worldslist.ws") {
        val bytes = worldListCache.snapshot()
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.allowBrowserRead()
        call.respondBytes(bytes, ContentType.Application.OctetStream)
    }

    get("/worlds.js") {
        val body = worldListCache.worldListJsSnapshot()
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.allowBrowserRead()
        call.respondText(body, ContentType.Application.Json)
    }

    get("/jav_config.ws") {
        val body =
            javConfigCache.snapshot()
                ?: run {
                    call.allowBrowserRead()
                    call.respondText(
                        "jav_config unavailable",
                        ContentType.Text.Plain,
                        HttpStatusCode.ServiceUnavailable,
                    )
                    return@get
                }
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.allowBrowserRead()
        call.respondText(body, ContentType.Text.Plain)
    }

    get("/health") {
        call.respondText("ok", ContentType.Text.Plain)
    }
}
