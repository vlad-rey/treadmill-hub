package io.github.vladrey.treadmillhub

import android.content.res.AssetManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.github.vladrey.treadmillhub.session.ConsoleReading
import io.github.vladrey.treadmillhub.session.ProfilePatch
import kotlinx.serialization.Serializable
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException

private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

@Serializable
private data class ProfileRef(val profileId: String? = null)

/** HTTP + WebSocket API хаба и статика веб-интерфейса из assets/web. */
class HubServer(private val hub: Hub, private val assets: AssetManager, port: Int) {
    private val engine: ApplicationEngine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
        install(WebSockets) { pingPeriodMillis = 15_000 }
        routing {
            get("/") { call.respondAsset("index.html") }
            // Service worker должен отдаваться из корня, чтобы управлять всем приложением
            get("/sw.js") { call.respondAsset("sw.js") }
            get("/static/{path...}") {
                val path = call.parameters.getAll("path")?.joinToString("/").orEmpty()
                if (path.contains("..")) call.respondText("", status = HttpStatusCode.BadRequest)
                else call.respondAsset(path)
            }

            get("/api/state") { call.respondJson(json.encodeToString(hub.snapshot.value)) }

            post("/api/control") {
                val req = runCatching { json.decodeFromString<ControlRequest>(call.receiveText()) }.getOrNull()
                if (req == null) {
                    call.respondJson("""{"ok":false,"message":"ожидается {action, value}"}""", HttpStatusCode.BadRequest)
                } else {
                    val r = hub.control(req)
                    call.respondJson(json.encodeToString(r), if (r.ok) HttpStatusCode.OK else HttpStatusCode.Conflict)
                }
            }

            get("/api/profiles") { call.respondJson(json.encodeToString(hub.profiles.all())) }
            post("/api/profiles") {
                val r = runCatching { hub.profiles.create(json.decodeFromString<ProfilePatch>(call.receiveText())) }
                r.fold({ call.respondJson(json.encodeToString(it)) }, { call.respondError(it) })
            }
            post("/api/profiles/{id}") {
                val id = call.parameters["id"].orEmpty()
                val r = runCatching { hub.profiles.update(id, json.decodeFromString<ProfilePatch>(call.receiveText())) }
                r.fold(
                    { if (it == null) call.respondJson("""{"error":"профиль не найден"}""", HttpStatusCode.NotFound) else call.respondJson(json.encodeToString(it)) },
                    { call.respondError(it) },
                )
            }
            // Итоги: сегодня / неделя / месяц / всё время. profile пустой — тренировки без владельца
            get("/api/stats") { call.respondJson(json.encodeToString(hub.stats(call.request.queryParameters["profile"]?.ifBlank { null }))) }

            get("/api/sessions") {
                val profile = call.request.queryParameters["profile"]
                val list = hub.history.list().let { all -> if (profile == null) all else all.filter { it.profileId == profile.ifBlank { null } } }
                call.respondJson(json.encodeToString(list))
            }
            post("/api/sessions/{id}/profile") {
                val id = call.parameters["id"]?.toLongOrNull()
                val body = runCatching { json.decodeFromString<ProfileRef>(call.receiveText()) }.getOrNull()
                val ok = id != null && body != null && (body.profileId == null || hub.profiles.get(body.profileId) != null) &&
                    hub.history.setProfile(id, body.profileId)
                call.respondJson("""{"ok":$ok}""", if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest)
            }
            get("/api/sessions/{id}") {
                val s = call.parameters["id"]?.toLongOrNull()?.let(hub.history::load)
                if (s == null) call.respondJson("""{"error":"не найдено"}""", HttpStatusCode.NotFound)
                else call.respondJson(json.encodeToString(s))
            }
            post("/api/sessions/{id}/console") {
                val id = call.parameters["id"]?.toLongOrNull()
                val reading = runCatching { json.decodeFromString<ConsoleReading>(call.receiveText()) }.getOrNull()
                val ok = id != null && reading != null && hub.history.setConsole(id, reading)
                call.respondJson("""{"ok":$ok}""", if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest)
            }

            get("/api/config") { call.respondJson(json.encodeToString(hub.config.toDto())) }
            post("/api/config") {
                val result = runCatching { hub.config.apply(json.decodeFromString<ConfigPatch>(call.receiveText())) }
                if (result.isSuccess) call.respondJson(json.encodeToString(hub.config.toDto()))
                else call.respondJson("""{"error":${json.encodeToString(result.exceptionOrNull()?.message ?: "ошибка")}}""", HttpStatusCode.BadRequest)
            }

            webSocket("/ws/live") {
                hub.snapshot.collect { send(Frame.Text(json.encodeToString(it))) }
            }
            webSocket("/ws/debug/ble") {
                hub.backend.frames.collect { send(Frame.Text(json.encodeToString(it))) }
            }
        }
    }

    fun start() { engine.start(wait = false) }
    fun stop() { engine.stop(500, 2_000) }

    private suspend fun io.ktor.server.application.ApplicationCall.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respondText(body, ContentType.Application.Json, status)

    private suspend fun io.ktor.server.application.ApplicationCall.respondError(e: Throwable) =
        respondJson("""{"error":${json.encodeToString(e.message ?: "ошибка")}}""", HttpStatusCode.BadRequest)

    private suspend fun io.ktor.server.application.ApplicationCall.respondAsset(path: String) {
        val bytes = try {
            assets.open("web/$path").use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            return respondText("не найдено", status = HttpStatusCode.NotFound)
        }
        respondBytes(bytes, contentTypeOf(path))
    }

    private fun contentTypeOf(path: String): ContentType = when (path.substringAfterLast('.')) {
        "html" -> ContentType.Text.Html
        "js" -> ContentType.Application.JavaScript
        "css" -> ContentType.Text.CSS
        "svg" -> ContentType.Image.SVG
        "png" -> ContentType.Image.PNG
        "json", "webmanifest" -> ContentType.Application.Json
        else -> ContentType.Application.OctetStream
    }
}
