package io.github.vladrey.treadmillhub.net

import kotlinx.serialization.Serializable

/** Результат замера скорости интернета (источник — роутер, подключение в планах). */
@Serializable
data class SpeedResult(
    val atMs: Long,
    val downMbps: Double? = null,
    val upMbps: Double? = null,
    val pingMs: Int? = null,
    val error: String? = null,
)
