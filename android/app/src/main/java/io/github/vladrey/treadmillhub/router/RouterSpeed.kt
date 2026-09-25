package io.github.vladrey.treadmillhub.router

import io.github.vladrey.treadmillhub.net.SpeedResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Speed test via the router's built-in Ookla Speedtest (like the "Internet Speed" button in the ASUS web UI):
 * `ookla_speedtest_exe.cgi` → poll `ookla_speedtest_get_result()` until a type=result entry → router history
 * (`ookla_speedtest_write_history.cgi`) and our own speed.json history. Scheduled at [times].
 */
class RouterSpeed(
    private val router: RouterWatch,
    dir: File,
    private val onResult: (SpeedResult) -> Unit = {},
    private val times: List<LocalTime> = listOf(LocalTime.of(7, 0), LocalTime.of(21, 0)),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val file = File(dir, "speed.json")
    private val results: MutableList<SpeedResult> =
        runCatching { json.decodeFromString<List<SpeedResult>>(file.readText()) }.getOrDefault(emptyList()).toMutableList()
    @Volatile var running = false
        private set

    @Synchronized fun all(): List<SpeedResult> = results.toList()

    fun start(scope: CoroutineScope, busy: () -> Boolean) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(60_000)
                if (!router.status.connected || running) continue
                val now = ZonedDateTime.now(zone)
                val due = times.map { now.toLocalDate().atTime(it).atZone(zone) }.lastOrNull { it <= now } ?: continue
                val last = all().lastOrNull()?.atMs ?: 0
                // This slot's test hasn't run yet and less than 2 h have passed since it started; wait during a workout
                if (last >= due.toInstant().toEpochMilli() || now.toInstant().toEpochMilli() - due.toInstant().toEpochMilli() > 2 * 3600_000L) continue
                if (busy()) continue
                run()
            }
        }
    }

    /** Blocking measurement (~30-60 s); call on Dispatchers.IO. */
    fun run(): SpeedResult {
        synchronized(this) { if (running) return SpeedResult(System.currentTimeMillis(), error = "замер уже идёт"); running = true }
        val started = System.currentTimeMillis()
        val r = try {
            measure(started)
        } catch (e: Exception) {
            SpeedResult(started, error = e.message ?: e.javaClass.simpleName)
        } finally {
            running = false
        }
        synchronized(this) {
            results += r
            while (results.size > 2000) results.removeAt(0)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString<List<SpeedResult>>(results))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        onResult(r)
        return r
    }

    private fun measure(started: Long): SpeedResult {
        router.use { rt ->
            rt.post("/set_ookla_speedtest_start_time.cgi", mapOf("ookla_start_time" to "$started"))
            rt.post("/ookla_speedtest_exe.cgi", mapOf("type" to "", "id" to ""))
        } ?: throw IllegalStateException("роутер не подключён")
        while (System.currentTimeMillis() - started < 120_000) {
            Thread.sleep(3_000)
            val arr = router.use { it.hook("ookla_speedtest_get_result();")["ookla_speedtest_get_result"] } as? JsonArray ?: continue
            val items = arr.mapNotNull { it as? JsonObject }
            if (items.any { it["error"] != null }) throw IllegalStateException("роутер не смог провести замер")
            val res = items.firstOrNull { it.s("type") == "result" } ?: continue
            // Result from a previous test (if the file hasn't been overwritten yet) — skip it
            val ts = res.s("timestamp")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            if (ts != null && ts < started - 60_000) continue
            saveToRouterHistory(res)
            return parseResult(res, ts ?: System.currentTimeMillis())
        }
        throw IllegalStateException("замер не закончился за 2 мин")
    }

    /** So the test result also shows up in the router's web UI (Adaptive QoS → Internet Speed). */
    private fun saveToRouterHistory(res: JsonObject) = runCatching {
        router.use { rt ->
            val old = (rt.hook("ookla_speedtest_get_history();")["ookla_speedtest_get_history"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.takeIf { o -> o.isNotEmpty() } }.orEmpty()
            val id = (res["result"] as? JsonObject)?.s("id")
            val list = (listOf(res) + old.filter { (it["result"] as? JsonObject)?.s("id") != id }).take(50)
            rt.post("/ookla_speedtest_write_history.cgi", mapOf("speedTest_history" to list.joinToString("\n") { it.toString() } + "\n"))
        }
    }

    companion object {
        private fun JsonObject.s(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

        /** The type=result entry from Ookla: bandwidth in bytes/s → Mbps. */
        fun parseResult(res: JsonObject, atMs: Long): SpeedResult {
            fun bw(k: String) = (res[k] as? JsonObject)?.get("bandwidth")?.jsonPrimitive?.longOrNull?.let { Math.round(it * 8 / 1e5) / 10.0 }
            val ping = (res["ping"] as? JsonObject)?.get("latency")?.jsonPrimitive?.doubleOrNull
            return SpeedResult(atMs, bw("download"), bw("upload"), ping?.let { Math.round(it).toInt() })
        }

        fun parseResult(text: String, atMs: Long) = parseResult(Json.parseToJsonElement(text).jsonObject, atMs)
    }
}
