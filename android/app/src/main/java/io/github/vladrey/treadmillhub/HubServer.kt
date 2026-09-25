package io.github.vladrey.treadmillhub

import android.content.res.AssetManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
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

/** HTTP + WebSocket API хаба и статика веб-интерфейса из assets/web. */
class HubServer(private val hub: Hub, private val assets: AssetManager, port: Int) {
    private val engine: ApplicationEngine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
        install(WebSockets) { pingPeriodMillis = 15_000 }
        routing {
            get("/") { call.respondAsset("index.html") }
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
