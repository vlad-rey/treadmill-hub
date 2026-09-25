package io.github.vladrey.treadmillhub.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/** Пользователь дорожки. Хранится на хабе; телефон помнит только свой id. */
@Serializable
data class Profile(val id: String, val name: String, val weightKg: Double, val maxSpeedKmh: Double)

@Serializable
data class ProfilePatch(val name: String? = null, val weightKg: Double? = null, val maxSpeedKmh: Double? = null)

class ProfileStore(private val file: File) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var profiles: List<Profile> =
        runCatching { json.decodeFromString<List<Profile>>(file.readText()) }.getOrDefault(emptyList())

    @Synchronized fun all(): List<Profile> = profiles

    @Synchronized fun get(id: String?): Profile? = id?.let { profiles.firstOrNull { p -> p.id == it } }

    @Synchronized
    fun create(p: ProfilePatch): Profile {
        val profile = Profile(
            id = UUID.randomUUID().toString().take(8),
            name = requireNotNull(p.name) { "нужно имя" },
            weightKg = p.weightKg ?: 75.0,
            maxSpeedKmh = p.maxSpeedKmh ?: 12.0,
        ).also(::validate)
        profiles = profiles + profile
        persist()
        return profile
    }

    @Synchronized
    fun update(id: String, p: ProfilePatch): Profile? {
        val old = get(id) ?: return null
        val new = old.copy(
            name = p.name ?: old.name,
            weightKg = p.weightKg ?: old.weightKg,
            maxSpeedKmh = p.maxSpeedKmh ?: old.maxSpeedKmh,
        ).also(::validate)
        profiles = profiles.map { if (it.id == id) new else it }
        persist()
        return new
    }

    private fun validate(p: Profile) {
        require(p.name.isNotBlank() && p.name.length <= 30) { "имя 1–30 символов" }
        require(p.weightKg in 30.0..250.0) { "вес 30–250 кг" }
        require(p.maxSpeedKmh in 1.0..16.0) { "лимит скорости 1–16 км/ч" }
    }

    private fun persist() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(profiles))
        tmp.renameTo(file)
    }
}

@Serializable
data class Totals(
    val sessions: Int = 0,
    val movingS: Double = 0.0,
    val distanceM: Double = 0.0,
    val kcalCalc: Double = 0.0,
    val kcalTreadmill: Double = 0.0,
)

@Serializable
data class ProfileStats(val profileId: String?, val today: Totals, val week: Totals, val month: Totals, val all: Totals)

/** Итоги по периодам: сегодня, неделя с понедельника, календарный месяц, всё время. */
object StatsCalculator {
    fun compute(profileId: String?, sessions: List<SessionSummary>, now: ZonedDateTime): ProfileStats {
        val zone = now.zone
        val today = now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val week = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant().toEpochMilli()
        val month = now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val mine = sessions.filter { it.profileId == profileId }
        fun sum(from: Long) = mine.filter { it.id >= from }.fold(Totals()) { t, s ->
            Totals(t.sessions + 1, t.movingS + s.movingS, t.distanceM + s.distanceM, t.kcalCalc + s.kcalCalc, t.kcalTreadmill + (s.kcalTreadmill ?: 0.0))
        }
        return ProfileStats(profileId, sum(today), sum(week), sum(month), sum(Long.MIN_VALUE))
    }

    fun now(): ZonedDateTime = ZonedDateTime.ofInstant(Instant.now(), ZoneId.systemDefault())
}
