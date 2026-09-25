package io.github.vladrey.treadmillhub.program

import io.github.vladrey.treadmillhub.treadmill.Limits
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Отрезок программы. [inclinePct] = null — наклон не меняется. */
@Serializable
data class Segment(val durationS: Int, val speedKmh: Double, val inclinePct: Double? = null)

/** Блок своей программы: шаги, повторённые [repeat] раз (интервалы). */
@Serializable
data class Block(val repeat: Int = 1, val steps: List<Segment>)

@Serializable
data class CustomProgram(
    val id: String,
    val profileId: String?,
    val name: String,
    val blocks: List<Block>,
) {
    fun segments(): List<Segment> = blocks.flatMap { b -> List(b.repeat) { b.steps }.flatten() }
}

@Serializable
data class CustomProgramInput(val profileId: String? = null, val name: String, val blocks: List<Block>)

// --- Встроенные программы пульта (таблицы из инструкции) ------------------------------------

@Serializable
data class BuiltinLevel(val level: Int, val speed: List<Int>, val incline: List<Int>? = null)

@Serializable
data class BuiltinProgram(val id: String, val name: String, val nameEn: String, val levels: List<BuiltinLevel>)

@Serializable
private data class BuiltinFile(val segments: Int, val defaultMinutes: Int, val programs: List<BuiltinProgram>)

@Serializable
data class ProgramInfo(val id: String, val name: String, val builtin: Boolean, val levels: Int, val totalS: Int?, val profileId: String? = null)

class BuiltinPrograms(jsonText: String) {
    private val file = Json { ignoreUnknownKeys = true }.decodeFromString<BuiltinFile>(jsonText)
    val defaultMinutes = file.defaultMinutes
    val all: List<BuiltinProgram> = file.programs

    fun get(id: String) = all.firstOrNull { it.id == id }

    /**
     * Время делится на отрезки поровну, как на пульте. Наклон из таблицы — уровень 1–15,
     * отдаём его как проценты (по FTMS дорожка работает в % 0–15; соответствие проверить).
     */
    fun segments(id: String, level: Int, minutes: Int): List<Segment>? {
        val lv = get(id)?.levels?.firstOrNull { it.level == level } ?: return null
        val n = lv.speed.size
        val total = minutes.coerceIn(5, 99) * 60
        return lv.speed.indices.map { i ->
            // остаток от деления распределяется по секунде на отрезок: 300 с / 18 → 16 или 17 с
            val d = (i + 1) * total / n - i * total / n
            Segment(d, lv.speed[i].toDouble(), lv.incline?.getOrNull(i)?.toDouble())
        }
    }
}

// --- Свои программы (хранятся на хабе, привязаны к профилю) ----------------------------------

class ProgramStore(private val file: File) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var programs: List<CustomProgram> =
        runCatching { json.decodeFromString<List<CustomProgram>>(file.readText()) }.getOrDefault(emptyList())

    @Synchronized fun all(): List<CustomProgram> = programs
    @Synchronized fun get(id: String): CustomProgram? = programs.firstOrNull { it.id == id }

    @Synchronized
    fun create(input: CustomProgramInput): CustomProgram {
        val p = CustomProgram(UUID.randomUUID().toString().take(8), input.profileId, input.name.trim(), input.blocks).also(::validate)
        programs = programs + p
        persist()
        return p
    }

    @Synchronized
    fun update(id: String, input: CustomProgramInput): CustomProgram? {
        val old = get(id) ?: return null
        val p = old.copy(name = input.name.trim(), blocks = input.blocks).also(::validate)
        programs = programs.map { if (it.id == id) p else it }
        persist()
        return p
    }

    @Synchronized
    fun delete(id: String): Boolean {
        if (get(id) == null) return false
        programs = programs.filterNot { it.id == id }
        persist()
        return true
    }

    private fun validate(p: CustomProgram) {
        require(p.name.isNotBlank() && p.name.length <= 40) { "название 1–40 символов" }
        require(p.blocks.isNotEmpty() && p.blocks.all { it.steps.isNotEmpty() && it.repeat in 1..50 }) { "нужен хотя бы один шаг; повторы 1–50" }
        val segs = p.segments()
        require(segs.size <= 500) { "слишком много отрезков" }
        require(segs.all { it.durationS in 5..3600 }) { "длительность шага 5 с – 60 мин" }
        require(segs.all { it.speedKmh in Limits.MIN_SPEED_KMH..Limits.MAX_SPEED_KMH }) { "скорость 1–16 км/ч" }
        require(segs.all { it.inclinePct == null || it.inclinePct in Limits.MIN_INCLINE_PCT..Limits.MAX_INCLINE_PCT }) { "наклон 0–15 %" }
    }

    private fun persist() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(programs))
        tmp.renameTo(file)
    }
}

/** Скорость программы не выше лимита профиля; шаг 0,1 км/ч. */
fun List<Segment>.capSpeed(maxKmh: Double) =
    map { it.copy(speedKmh = ((minOf(it.speedKmh, maxKmh)) * 10).roundToInt() / 10.0) }
