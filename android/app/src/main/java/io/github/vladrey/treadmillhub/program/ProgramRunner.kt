package io.github.vladrey.treadmillhub.program

import io.github.vladrey.treadmillhub.treadmill.Command
import io.github.vladrey.treadmillhub.treadmill.Phase
import kotlinx.serialization.Serializable

@Serializable
enum class RunState { WAITING, RUNNING, DONE, CANCELLED }

@Serializable
data class ProgramStatus(
    val id: String,
    val name: String,
    val state: RunState,
    val segments: List<Segment>,
    val index: Int,
    val elapsedS: Double,
    val totalS: Int,
    val segmentRemainingS: Double,
)

/**
 * Program execution. Pure logic with no BLE: [tick] takes the treadmill phase and elapsed time,
 * and returns commands the hub should send. Program time only advances while running — it's
 * frozen on pause or connection loss.
 */
class ProgramRunner(val id: String, val name: String, val segments: List<Segment>) {
    var state = RunState.WAITING
        private set
    var elapsedS = 0.0
        private set
    var index = -1
        private set
    private var waitedS = 0.0
    private var sawRunning = false
    val totalS = segments.sumOf { it.durationS }

    fun tick(phase: Phase, dtS: Double): List<Command> {
        when (state) {
            RunState.WAITING -> {
                if (phase == Phase.RUNNING) {
                    state = RunState.RUNNING
                    sawRunning = true
                    return enter(0)
                }
                waitedS += dtS
                if (waitedS > 20) state = RunState.CANCELLED // treadmill never started moving
            }
            RunState.RUNNING -> {
                // Stopped from the console or a button — the program is cancelled
                if (phase == Phase.IDLE || phase == Phase.FINISHED || phase == Phase.STOPPING) {
                    state = RunState.CANCELLED
                    return emptyList()
                }
                if (phase != Phase.RUNNING) return emptyList()
                elapsedS += dtS
                if (elapsedS >= totalS) {
                    state = RunState.DONE
                    return listOf(Command.Stop)
                }
                val i = indexAt(elapsedS)
                if (i != index) return enter(i)
            }
            RunState.DONE, RunState.CANCELLED -> Unit
        }
        return emptyList()
    }

    fun cancel() { if (state == RunState.WAITING || state == RunState.RUNNING) state = RunState.CANCELLED }

    val finished get() = state == RunState.DONE || state == RunState.CANCELLED

    private fun indexAt(t: Double): Int {
        var acc = 0
        segments.forEachIndexed { i, s -> acc += s.durationS; if (t < acc) return i }
        return segments.lastIndex
    }

    private fun enter(i: Int): List<Command> {
        index = i
        val s = segments[i]
        return listOfNotNull(Command.Speed(s.speedKmh), s.inclinePct?.let { Command.Incline(it) })
    }

    fun status(): ProgramStatus {
        val before = segments.take(index.coerceAtLeast(0)).sumOf { it.durationS }
        val segLeft = if (index >= 0) (before + segments[index].durationS - elapsedS).coerceAtLeast(0.0) else 0.0
        return ProgramStatus(id, name, state, segments, index, elapsedS, totalS, segLeft)
    }
}
