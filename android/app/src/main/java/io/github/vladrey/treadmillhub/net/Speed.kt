package io.github.vladrey.treadmillhub.net

import kotlinx.serialization.Serializable

/** Internet speed test result (source — router; more connections planned). */
@Serializable
data class SpeedResult(
    val atMs: Long,
    val downMbps: Double? = null,
    val upMbps: Double? = null,
    val pingMs: Int? = null,
    val error: String? = null,
)
