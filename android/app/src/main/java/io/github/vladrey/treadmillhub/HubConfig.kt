package io.github.vladrey.treadmillhub

import android.content.Context
import kotlinx.serialization.Serializable

/** Настройки хаба. Хранятся на телефоне (SharedPreferences), не в репозитории. */
class HubConfig(context: Context) {
    private val prefs = context.getSharedPreferences("hub", Context.MODE_PRIVATE)

    /** MAC дорожки. Если не задан — хаб ищет устройство с сервисом FTMS. */
    var deviceAddress: String?
        get() = prefs.getString("deviceAddress", null)
        set(v) = prefs.edit().putString("deviceAddress", v).apply()

    /** "ftms" — реальная дорожка, "sim" — симулятор. Меняется с перезапуском сервиса. */
    var backend: String
        get() = prefs.getString("backend", "ftms")!!
        set(v) = prefs.edit().putString("backend", v).apply()

    var weightKg: Double
        get() = prefs.getFloat("weightKg", 75f).toDouble()
        set(v) = prefs.edit().putFloat("weightKg", v.toFloat()).apply()

    /** Лимит скорости для команд с хаба (дорожка умеет до 16 км/ч). */
    var maxSpeedKmh: Double
        get() = prefs.getFloat("maxSpeedKmh", 12f).toDouble()
        set(v) = prefs.edit().putFloat("maxSpeedKmh", v.toFloat()).apply()

    val port: Int get() = prefs.getInt("port", 8080)

    var lastBackupMs: Long?
        get() = prefs.getLong("lastBackupMs", 0L).takeIf { it > 0 }
        set(v) = prefs.edit().putLong("lastBackupMs", v ?: 0L).apply()

    fun toDto() = ConfigDto(deviceAddress, backend, weightKg, maxSpeedKmh)

    fun apply(dto: ConfigPatch) {
        dto.deviceAddress?.let { deviceAddress = it.ifBlank { null } }
        dto.backend?.let { require(it == "ftms" || it == "sim") { "backend: ftms или sim" }; backend = it }
        dto.weightKg?.let { require(it in 30.0..250.0) { "вес 30–250 кг" }; weightKg = it }
        dto.maxSpeedKmh?.let { require(it in 1.0..16.0) { "лимит 1–16 км/ч" }; maxSpeedKmh = it }
    }
}

@Serializable
data class ConfigDto(val deviceAddress: String?, val backend: String, val weightKg: Double, val maxSpeedKmh: Double)

@Serializable
data class ConfigPatch(
    val deviceAddress: String? = null,
    val backend: String? = null,
    val weightKg: Double? = null,
    val maxSpeedKmh: Double? = null,
)
