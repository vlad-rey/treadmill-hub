package io.github.vladrey.treadmillhub.treadmill

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** Виртуальная дорожка: для разработки интерфейса и тестов без риска. Ведёт себя как T12B. */
class SimulatorBackend(private val maxSpeed: () -> Double) : TreadmillBackend {
    override val name = "sim"
    private val _state = MutableStateFlow(TreadmillState(connection = Connection.CONNECTED))
    override val state = _state.asStateFlow()
    override val frames = MutableSharedFlow<BleFrame>().asSharedFlow()

    private var job: Job? = null
    private var targetSpeed = 0.0
    private var kcal = 0.0

    override fun start(scope: CoroutineScope) {
        job = scope.launch {
            while (isActive) {
                delay(1_000)
                tick()
            }
        }
    }

    override fun close() { job?.cancel() }

    private fun tick() = _state.update { s ->
        val now = System.currentTimeMillis()
        when (s.phase) {
            Phase.COUNTDOWN -> {
                val c = (s.countdown ?: 3) - 1
                if (c > 0) s.copy(countdown = c, updatedAtMs = now)
                else { targetSpeed = 1.0; s.copy(phase = Phase.RUNNING, countdown = null, speedKmh = 1.0, updatedAtMs = now) }
            }
            Phase.RUNNING -> {
                val speed = if (s.speedKmh < targetSpeed) min(targetSpeed, s.speedKmh + 1.0) else max(targetSpeed, s.speedKmh - 1.0)
                kcal += speed * 0.015
                s.copy(speedKmh = speed, elapsedS = s.elapsedS + 1, kcal = kcal, updatedAtMs = now)
            }
            Phase.STOPPING -> {
                val speed = max(0.0, s.speedKmh - 0.6)
                s.copy(speedKmh = speed, phase = if (speed == 0.0) Phase.FINISHED else Phase.STOPPING, elapsedS = s.elapsedS + 1, updatedAtMs = now)
            }
            else -> s.copy(updatedAtMs = now)
        }
    }

    override suspend fun command(cmd: Command): CommandResult {
        Limits.check(cmd, maxSpeed())?.let { return CommandResult(false, it) }
        _state.update { s ->
            when (cmd) {
                Command.Start -> if (s.phase == Phase.PAUSED) s.copy(phase = Phase.RUNNING)
                else { kcal = 0.0; s.copy(phase = Phase.COUNTDOWN, countdown = 3, elapsedS = 0, kcal = 0.0) }
                Command.Stop -> s.copy(phase = Phase.STOPPING)
                Command.Pause -> s.copy(phase = Phase.PAUSED, speedKmh = 0.0)
                is Command.Speed -> { targetSpeed = cmd.kmh; s }
                is Command.Incline -> s.copy(inclinePct = cmd.pct)
            }
        }
        return CommandResult(true, "OK (симулятор)")
    }
}
