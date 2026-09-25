package io.github.vladrey.treadmillhub

import android.content.Context
import kotlinx.serialization.Serializable

/** Hub settings. Stored on the phone (SharedPreferences), not in the repository. */
class HubConfig(context: Context) {
    private val prefs = context.getSharedPreferences("hub", Context.MODE_PRIVATE)

    /** Treadmill MAC address. If not set, the hub scans for a device with the FTMS service. */
    var deviceAddress: String?
        get() = prefs.getString("deviceAddress", null)
        set(v) = prefs.edit().putString("deviceAddress", v).apply()

    /** "ftms" — real treadmill, "sim" — simulator. Takes effect on service restart. */
    var backend: String
        get() = prefs.getString("backend", "ftms")!!
        set(v) = prefs.edit().putString("backend", v).apply()

    var weightKg: Double
        get() = prefs.getFloat("weightKg", 75f).toDouble()
        set(v) = prefs.edit().putFloat("weightKg", v.toFloat()).apply()

    /** Speed limit for commands from the hub (the treadmill supports up to 16 km/h). */
    var maxSpeedKmh: Double
        get() = prefs.getFloat("maxSpeedKmh", 12f).toDouble()
        set(v) = prefs.edit().putFloat("maxSpeedKmh", v.toFloat()).apply()

    val port: Int get() = prefs.getInt("port", 8080)

    /** Telegram: bot token and the owner's chat id. Stored only on the phone. */
    var telegramToken: String?
        get() = prefs.getString("telegramToken", null)
        set(v) = prefs.edit().putString("telegramToken", v).apply()
    var telegramChatId: String?
        get() = prefs.getString("telegramChatId", null)
        set(v) = prefs.edit().putString("telegramChatId", v).apply()

    /** ASUS router: admin login and password. Stored only on the phone, never exposed. */
    var routerUser: String?
        get() = prefs.getString("routerUser", null)
        set(v) = prefs.edit().putString("routerUser", v).apply()
    var routerPassword: String?
        get() = prefs.getString("routerPassword", null)
        set(v) = prefs.edit().putString("routerPassword", v).apply()

    var lastBackupMs: Long?
        get() = prefs.getLong("lastBackupMs", 0L).takeIf { it > 0 }
        set(v) = prefs.edit().putLong("lastBackupMs", v ?: 0L).apply()

    fun toDto() = ConfigDto(deviceAddress, backend, weightKg, maxSpeedKmh, telegramToken != null, telegramChatId)

    fun apply(dto: ConfigPatch) {
        dto.deviceAddress?.let { deviceAddress = it.ifBlank { null } }
        dto.backend?.let { require(it == "ftms" || it == "sim") { "backend: ftms или sim" }; backend = it }
        dto.weightKg?.let { require(it in 30.0..250.0) { "вес 30–250 кг" }; weightKg = it }
        dto.maxSpeedKmh?.let { require(it in 1.0..16.0) { "лимит 1–16 км/ч" }; maxSpeedKmh = it }
        dto.telegramToken?.let { telegramToken = it.ifBlank { null } }
        dto.telegramChatId?.let { telegramChatId = it.ifBlank { null } }
    }
}

@Serializable
data class ConfigDto(
    val deviceAddress: String?, val backend: String, val weightKg: Double, val maxSpeedKmh: Double,
    /** The token itself is never exposed — only a flag that it's set. */
    val telegramConfigured: Boolean = false, val telegramChatId: String? = null,
)

@Serializable
data class ConfigPatch(
    val deviceAddress: String? = null,
    val backend: String? = null,
    val weightKg: Double? = null,
    val maxSpeedKmh: Double? = null,
    val telegramToken: String? = null,
    val telegramChatId: String? = null,
)
