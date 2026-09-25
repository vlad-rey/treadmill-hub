package io.github.vladrey.treadmillhub.router

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Ошибка входа: неверный логин/пароль — повторять нельзя (роутер заблокирует вход). */
class RouterAuthException(message: String, val permanent: Boolean) : Exception(message)

/** Клиент роутера из списка клиентов ASUSWRT (`get_clientlist()`). */
@Serializable
data class RouterClient(
    val mac: String,
    val ip: String?,
    val name: String?,
    val vendor: String?,
    val online: Boolean,
    /** "кабель", "2,4 ГГц", "5 ГГц", "6 ГГц" или null. */
    val link: String?,
    val rssi: Int?,
)

/**
 * Внутренний HTTP-интерфейс ASUSWRT (им пользуются приложение ASUS Router и Home Assistant):
 * вход `login.cgi` → `asus_token`, данные `appGet.cgi?hook=…`. Вход под видом приложения, чтобы
 * не выкидывать владельца из веб-админки.
 */
class AsusRouter(private val host: String, private val user: String, private val password: String) {
    private var token: String? = null

    fun login() {
        val auth = java.util.Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        val (code, body) = http("/login.cgi", "login_authorization=${URLEncoder.encode(auth, "UTF-8")}", withToken = false)
        val o = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val t = o?.get("asus_token")?.jsonPrimitive?.content
        if (code == 200 && !t.isNullOrBlank()) { token = t; return }
        val err = o?.get("error_status")?.jsonPrimitive?.content
        throw when (err) {
            "3" -> RouterAuthException("неверный логин или пароль", permanent = true)
            "7", "10" -> RouterAuthException("роутер временно заблокировал вход после неудачных попыток", permanent = false)
            else -> RouterAuthException("роутер не пустил: код $code${err?.let { ", ошибка $it" } ?: ""}", permanent = false)
        }
    }

    fun logout() = runCatching { if (token != null) http("/Logout.asp", null) }.also { token = null }

    /** Вызов hook'ов ASUSWRT, например `nvram_get(productid);get_clientlist();`. Перелогин при истёкшем токене. */
    fun hook(hooks: String): JsonObject {
        if (token == null) login()
        var (code, body) = http("/appGet.cgi", "hook=${URLEncoder.encode(hooks, "UTF-8")}")
        if (code != 200 || !body.trimStart().startsWith("{") || body.contains("\"error_status\"")) {
            login()
            val r = http("/appGet.cgi", "hook=${URLEncoder.encode(hooks, "UTF-8")}")
            code = r.first; body = r.second
        }
        return runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse { throw IllegalStateException("роутер ответил не JSON (код $code)") }
    }

    /** Страница или скрипт веб-интерфейса — для изучения (только отладка, не для пользователей). */
    fun page(path: String): Pair<Int, String> {
        if (token == null) login()
        return http(path, null)
    }

    /** POST формы на CGI роутера (например, запуск встроенного замера скорости). */
    fun post(path: String, params: Map<String, String>): Pair<Int, String> {
        if (token == null) login()
        return http(path, params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" })
    }

    fun clients(): List<RouterClient> = parseClients(hook("get_clientlist();")["get_clientlist"] as? JsonObject)

    fun model(): String? = runCatching {
        val o = hook("nvram_get(productid);nvram_get(firmver);nvram_get(buildno);")
        listOfNotNull(o.str("productid"), o.str("firmver")?.let { fw -> "прошивка $fw" + (o.str("buildno")?.let { ".$it" } ?: "") }).joinToString(", ")
    }.getOrNull()

    /** Счётчики интернета, байты: (приём, отдача). */
    fun wanBytes(): Pair<Long, Long>? = runCatching {
        val nd = hook("netdev(appobj);")["netdev"]?.jsonObject ?: return null
        fun hex(k: String) = nd[k]?.jsonPrimitive?.content?.removePrefix("0x")?.toLongOrNull(16)
        val rx = hex("INTERNET_rx") ?: return null
        val tx = hex("INTERNET_tx") ?: return null
        rx to tx
    }.getOrNull()

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun http(path: String, form: String?, withToken: Boolean = true): Pair<Int, String> {
        val c = URL("http://$host$path").openConnection() as HttpURLConnection
        c.connectTimeout = 5_000
        c.readTimeout = 15_000
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Referer", "http://$host/")
        if (withToken) token?.let { c.setRequestProperty("Cookie", "asus_token=$it") }
        if (form != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(form.toByteArray()) }
        }
        val code = c.responseCode
        val body = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        c.disconnect()
        return code to body
    }

    companion object {
        const val USER_AGENT = "asusrouter--DUTUtil-"
        private val MAC = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

        fun parseClients(list: JsonObject?): List<RouterClient> = list.orEmpty().mapNotNull { (k, v) ->
            if (!MAC.matches(k) || v !is JsonObject) return@mapNotNull null
            fun s(key: String) = (v[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            val link = when (s("isWL")) {
                "0" -> "кабель"; "1" -> "2,4 ГГц"; "2", "3" -> "5 ГГц"; "4", "5" -> "6 ГГц"; else -> null
            }
            RouterClient(
                mac = k.lowercase(),
                ip = s("ip"),
                name = s("nickName") ?: s("name"),
                vendor = s("vendor"),
                online = s("isOnline") == "1",
                link = link,
                rssi = s("rssi")?.toIntOrNull()?.takeIf { it != 0 },
            )
        }

        private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
    }
}
