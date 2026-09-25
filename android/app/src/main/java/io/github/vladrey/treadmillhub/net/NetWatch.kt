package io.github.vladrey.treadmillhub.net

import android.content.Context
import android.net.wifi.WifiManager
import io.github.vladrey.treadmillhub.gamification.Telegram
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

enum class NetKind { ROUTER, INTERNET }

@Serializable
data class NetOutage(val kind: NetKind, val startMs: Long, val endMs: Long? = null)

@Serializable
data class NetState(
    val routerOk: Boolean? = null,
    val internetOk: Boolean? = null,
    val routerMs: Int? = null,
    val internetMs: Int? = null,
    val checkedAtMs: Long = 0,
    /** Confirmed outage currently in progress. */
    val outage: NetOutage? = null,
)

/**
 * Decides from probe results whether there is an outage. An outage is confirmed after [confirm]
 * consecutive failed probes (start = the first failed one), and ends on the first successful one. If the
 * router ever didn't respond — it's a "router" outage, otherwise "internet" (router was up, ISP dropped).
 * Returns (closed outage, text for Telegram).
 */
class NetTracker(private val confirm: Int = 3, private val notifyMinMs: Long = 60_000, private val zone: ZoneId = ZoneId.systemDefault()) {
    var outage: NetOutage? = null
        private set
    private var fails = 0
    private var firstFailMs = 0L
    private var routerFailed = false

    fun onProbe(ms: Long, routerOk: Boolean, internetOk: Boolean): Pair<NetOutage, String?>? {
        if (routerOk && internetOk) {
            fails = 0; routerFailed = false
            val o = outage ?: return null
            outage = null
            val closed = o.copy(endMs = ms)
            return closed to (if (ms - o.startMs >= notifyMinMs) recoveredText(closed) else null)
        }
        if (fails == 0) firstFailMs = ms
        fails++
        if (!routerOk) routerFailed = true
        val kind = if (routerFailed) NetKind.ROUTER else NetKind.INTERNET
        if (outage == null && fails >= confirm) outage = NetOutage(kind, firstFailMs)
        else if (outage != null && kind == NetKind.ROUTER) outage = outage!!.copy(kind = kind)
        return null
    }

    private fun recoveredText(o: NetOutage): String {
        val span = "с ${clock(o.startMs)} до ${clock(o.endMs!!)} (${duration(o.endMs - o.startMs)})"
        return when (o.kind) {
            NetKind.ROUTER -> "📶 Роутер снова на связи. Хаб не видел его $span — роутер выключался, перезагружался или пропадал Wi-Fi."
            NetKind.INTERNET -> "🌐 Интернет вернулся. Не было $span; роутер при этом работал — пропадал провайдер."
        }
    }

    private fun clock(ms: Long) = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(ms))

    companion object {
        fun duration(ms: Long): String {
            val m = (ms / 60_000).toInt()
            return when {
                m < 1 -> "меньше минуты"
                m < 60 -> "$m мин"
                else -> "${m / 60} ч ${m % 60} мин"
            }
        }
    }
}

/** Every [intervalMs] pings the router (Wi-Fi gateway) and checks internet access; log — net-outages.json. */
class NetWatch(private val context: Context, dir: File, private val telegram: Telegram, private val intervalMs: Long = 20_000) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val file = File(dir, "net-outages.json")
    private val log: MutableList<NetOutage> =
        runCatching { json.decodeFromString<List<NetOutage>>(file.readText()) }.getOrDefault(emptyList()).toMutableList()
    private val tracker = NetTracker()
    @Volatile var state = NetState()
        private set

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val gw = gateway()
                val routerMs = gw?.let { ping(it) ?: tcp(it, 53, refusedOk = true) ?: tcp(it, 80, refusedOk = true) }
                val internetMs = INTERNET_HOSTS.firstNotNullOfOrNull { (h, p) -> tcp(h, p) }
                val now = System.currentTimeMillis()
                val closed = tracker.onProbe(now, routerMs != null, internetMs != null)
                state = NetState(routerMs != null, internetMs != null, routerMs, internetMs, now, tracker.outage)
                if (closed != null) {
                    synchronized(log) { log += closed.first; while (log.size > 500) log.removeAt(0); save() }
                    closed.second?.let { telegram.send(scope, it) }
                }
                delay(intervalMs)
            }
        }
    }

    /** Outage log, newest first; the one currently in progress comes first. */
    fun outages(limit: Int = 100): List<NetOutage> =
        (listOfNotNull(tracker.outage) + synchronized(log) { log.sortedByDescending { it.startMs } }).take(limit)

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString<List<NetOutage>>(log))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun gateway(): String? = runCatching {
        @Suppress("DEPRECATION")
        val g = context.applicationContext.getSystemService(WifiManager::class.java).dhcpInfo.gateway
        if (g == 0) null else "${g and 0xFF}.${g shr 8 and 0xFF}.${g shr 16 and 0xFF}.${g shr 24 and 0xFF}"
    }.getOrNull()

    /** ICMP ping via the system utility (allowed for a regular app); response time, ms, or null. */
    private fun ping(host: String): Int? = runCatching {
        val p = ProcessBuilder("/system/bin/ping", "-c", "2", "-W", "2", host).redirectErrorStream(true).start()
        if (!p.waitFor(6, TimeUnit.SECONDS)) { p.destroy(); return null }
        val out = p.inputStream.bufferedReader().readText()
        if (p.exitValue() != 0) return null
        Regex("time=([0-9.]+)").find(out)?.groupValues?.get(1)?.toDouble()?.toInt() ?: 0
    }.getOrNull()

    /** TCP connection time, ms, or null. [refusedOk]: a connection refusal also means the host is reachable. */
    private fun tcp(host: String, port: Int, refusedOk: Boolean = false): Int? {
        val t0 = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), 3_000) }
            ((System.nanoTime() - t0) / 1_000_000).toInt()
        } catch (e: java.net.ConnectException) {
            if (refusedOk && e.message?.contains("refused", ignoreCase = true) == true) ((System.nanoTime() - t0) / 1_000_000).toInt() else null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val INTERNET_HOSTS = listOf("1.1.1.1" to 443, "8.8.8.8" to 443, "9.9.9.9" to 443)
    }
}
