package io.github.vladrey.treadmillhub.net

import android.content.Context
import android.net.wifi.WifiManager
import io.github.vladrey.treadmillhub.gamification.Telegram
import io.github.vladrey.treadmillhub.router.RouterClient
import io.github.vladrey.treadmillhub.router.RouterWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class NetDevice(
    val mac: String,
    val ip: String,
    val name: String? = null,
    val hostname: String? = null,
    /** Vendor and connection info — from the ASUS router, if it's connected. */
    val vendor: String? = null,
    val link: String? = null,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    /** Own device: confirmed by the owner or seen during the first day of observation. */
    val known: Boolean,
) {
    /** Random (locally administered) MAC — what phones and tablets do. */
    val randomMac: Boolean get() = mac.substring(0, 2).toIntOrNull(16)?.and(0x02) == 0x02
}

@Serializable
private data class DeviceFile(val learnUntilMs: Long = 0, val devices: List<NetDevice> = emptyList())

@Serializable
data class DevicePatch(val name: String? = null, val known: Boolean? = null)

/**
 * Devices on the network by MAC. The first [learnMs] after the initial run is a learning period: everything
 * seen is treated as known. After that, a new MAC is an unknown device (a message is sent to the owner).
 */
class DeviceRegistry(private val file: File, private val learnMs: Long = 24 * 3600_000L) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var data: DeviceFile = runCatching { json.decodeFromString<DeviceFile>(file.readText()) }.getOrDefault(DeviceFile())

    @Synchronized fun all(): List<NetDevice> = data.devices.sortedWith(compareBy<NetDevice> { it.known }.thenByDescending { it.lastSeenMs })
    @Synchronized fun learning(now: Long) = data.learnUntilMs == 0L || now < data.learnUntilMs
    @Synchronized fun learnUntilMs() = data.learnUntilMs

    /** Mark seen (ip, mac) pairs; return unknown devices seen for the first time. */
    @Synchronized
    fun seen(list: List<Pair<String, String>>, now: Long): List<NetDevice> {
        if (data.learnUntilMs == 0L) data = data.copy(learnUntilMs = now + learnMs)
        val learning = now < data.learnUntilMs
        val byMac = data.devices.associateBy { it.mac }.toMutableMap()
        val fresh = ArrayList<NetDevice>()
        for ((ip, rawMac) in list) {
            val mac = rawMac.lowercase()
            val d = byMac[mac]
            if (d == null) {
                val n = NetDevice(mac, ip, firstSeenMs = now, lastSeenMs = now, known = learning)
                byMac[mac] = n
                if (!learning) fresh += n
            } else {
                byMac[mac] = d.copy(ip = ip, lastSeenMs = now)
            }
        }
        data = data.copy(devices = byMac.values.toList())
        save()
        return fresh
    }

    @Synchronized
    fun setHostname(mac: String, hostname: String) {
        data = data.copy(devices = data.devices.map { if (it.mac == mac) it.copy(hostname = hostname) else it })
        save()
    }

    /** Info from the router: client name, vendor, connection. */
    @Synchronized
    fun setInfo(info: Map<String, RouterClient>) {
        data = data.copy(devices = data.devices.map { d ->
            val c = info[d.mac] ?: return@map d
            d.copy(hostname = c.name ?: d.hostname, vendor = c.vendor ?: d.vendor, link = c.link ?: d.link)
        })
        save()
    }

    /** Unnamed devices whose MAC is 2 less than a station's Bluetooth address — that station's Wi-Fi. */
    @Synchronized
    fun nameStations(ble: Map<String, String>) {
        if (ble.isEmpty()) return
        val byWifi = ble.mapNotNull { (addr, name) -> wifiMacOfBle(addr)?.let { it to name } }.toMap()
        if (data.devices.none { it.name == null && it.mac in byWifi }) return
        data = data.copy(devices = data.devices.map { d -> byWifi[d.mac]?.takeIf { d.name == null }?.let { d.copy(name = "⚡ $it", known = true) } ?: d })
        save()
    }

    @Synchronized
    fun patch(mac: String, p: DevicePatch): NetDevice? {
        val m = mac.lowercase()
        if (data.devices.none { it.mac == m }) return null
        p.name?.let { require(it.length <= 40) { "имя до 40 символов" } }
        data = data.copy(devices = data.devices.map {
            if (it.mac != m) it else it.copy(name = p.name?.trim()?.ifBlank { null } ?: it.name.takeIf { p.name == null }, known = p.known ?: it.known)
        })
        save()
        return data.devices.first { it.mac == m }
    }

    @Synchronized
    fun remove(mac: String): Boolean {
        val before = data.devices.size
        data = data.copy(devices = data.devices.filter { it.mac != mac.lowercase() })
        save()
        return data.devices.size < before
    }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(data))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        /** ESP32: Wi-Fi MAC = Bluetooth MAC − 2. */
        fun wifiMacOfBle(ble: String): String? {
            val v = ble.replace(":", "").toLongOrNull(16) ?: return null
            return String.format("%012x", v - 2).chunked(2).joinToString(":")
        }

        /** Parsing /proc/net/arp: only complete entries (flag 0x2) on [iface]. */
        fun parseArp(text: String, iface: String = "wlan0"): List<Pair<String, String>> = text.lines().drop(1).mapNotNull { line ->
            val f = line.trim().split(Regex("\\s+"))
            if (f.size < 6 || f[5] != iface) return@mapNotNull null
            val flags = f[2].removePrefix("0x").toIntOrNull(16) ?: 0
            if (flags and 0x2 == 0 || f[3] == "00:00:00:00:00:00") null else f[0] to f[3].lowercase()
        }
    }
}

/**
 * Every [intervalMs]: clients from the ASUS router (if connected) plus a /24 subnet sweep — a UDP packet to
 * each address populates the ARP table, which we then read.
 */
class DeviceWatch(
    private val context: Context, dir: File, private val telegram: Telegram, private val router: RouterWatch,
    /** Station Bluetooth address → its name: we identify a station's Wi-Fi module (ESP32) by MAC = Bluetooth − 2. */
    private val stations: () -> Map<String, String> = { emptyMap() },
    private val intervalMs: Long = 5 * 60_000L,
) {
    val registry = DeviceRegistry(File(dir, "devices.json"))
    @Volatile var lastScanMs = 0L
        private set

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            delay(30_000) // after boot — give Wi-Fi time to come up
            while (isActive) {
                runCatching { scan(scope) }
                delay(intervalMs)
            }
        }
    }

    private suspend fun scan(scope: CoroutineScope) {
        @Suppress("DEPRECATION")
        val ipInt = context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo.ipAddress
        if (ipInt == 0) return
        val prefix = "${ipInt and 0xFF}.${ipInt shr 8 and 0xFF}.${ipInt shr 16 and 0xFF}."
        val self = ipInt shr 24 and 0xFF
        DatagramSocket().use { s ->
            val p = ByteArray(1)
            for (i in 1..254) if (i != self) runCatching { s.send(DatagramPacket(p, 1, InetAddress.getByName(prefix + i), 9)) }
        }
        delay(4_000)
        val arp = DeviceRegistry.parseArp(readArp()).filter { it.first.startsWith(prefix) }
        val fromRouter = if (System.currentTimeMillis() - router.status.lastOkMs < 2 * intervalMs) router.clients else emptyList()
        val routerOnline = fromRouter.filter { it.online && it.ip != null }.map { it.ip!! to it.mac }
        val seen = (routerOnline + arp).distinctBy { it.second }
        if (seen.isEmpty()) return
        val now = System.currentTimeMillis()
        val fresh = registry.seen(seen, now)
        if (fromRouter.isNotEmpty()) registry.setInfo(fromRouter.associateBy { it.mac })
        registry.nameStations(stations())
        lastScanMs = now
        // Names from the router (DHCP) — for new and still-unnamed devices
        registry.all().filter { it.hostname == null && it.lastSeenMs == now }.take(8).forEach { d ->
            runCatching { InetAddress.getByName(d.ip).canonicalHostName }.getOrNull()
                ?.takeIf { it != d.ip }?.let { registry.setHostname(d.mac, it.removeSuffix(".lan").removeSuffix(".local")) }
        }
        for (f in fresh) {
            val d = registry.all().firstOrNull { it.mac == f.mac } ?: f
            telegram.send(scope, "📱 Новое устройство в сети: ${d.hostname ?: "без имени"}" + (d.vendor?.let { " ($it)" } ?: "") +
                (d.link?.let { " · $it" } ?: "") + " · ${d.ip} · MAC ${d.mac}" +
                (if (d.randomMac) " (случайный MAC — скорее телефон или планшет)" else "") +
                ".\nЕсли это своё — отметьте на странице «Сеть».")
        }
    }

    /** /proc/net/arp; if the app isn't allowed to read it — via root. */
    private fun readArp(): String {
        val direct = runCatching { File("/proc/net/arp").readText() }.getOrDefault("")
        if (direct.lines().size > 1) return direct
        return runCatching {
            val p = ProcessBuilder("su", "-c", "cat /proc/net/arp").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().use { it.readText() }.also { p.waitFor() }
        }.getOrDefault("")
    }
}
