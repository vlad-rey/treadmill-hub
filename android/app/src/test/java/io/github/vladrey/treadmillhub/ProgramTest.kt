package io.github.vladrey.treadmillhub

import io.github.vladrey.treadmillhub.program.Block
import io.github.vladrey.treadmillhub.program.BuiltinPrograms
import io.github.vladrey.treadmillhub.program.CustomProgram
import io.github.vladrey.treadmillhub.program.ProgramRunner
import io.github.vladrey.treadmillhub.program.RunState
import io.github.vladrey.treadmillhub.program.Segment
import io.github.vladrey.treadmillhub.program.capSpeed
import io.github.vladrey.treadmillhub.treadmill.Command
import io.github.vladrey.treadmillhub.treadmill.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProgramTest {
    private val builtin = BuiltinPrograms(File("../../protocol/programs/programs-t12b.json").readText())

    @Test fun builtinTablesSplitTimeIntoEighteenSegments() {
        assertEquals(8, builtin.all.size)
        val segs = builtin.segments("P2", 8, 30)!!
        assertEquals(18, segs.size)
        assertEquals(30 * 60, segs.sumOf { it.durationS })
        assertEquals(4.0, segs[0].speedKmh, 0.0)     // P2 ур. 8: скорость 4, 6, 8…
        assertEquals(5.0, segs[0].inclinePct!!, 0.0) // наклон 5, 7, 9…
        // 5 минут не делятся на 18 поровну — отрезки по 16–17 с, сумма точно 300 с
        val five = builtin.segments("P1", 1, 5)!!
        assertEquals(5 * 60, five.sumOf { it.durationS })
        assertTrue(five.all { it.durationS in 16..17 })
        assertNull(builtin.segments("P1", 1, 30)!![0].inclinePct) // P1 меняет только скорость
    }

    @Test fun speedIsCappedByProfileLimit() {
        val capped = builtin.segments("P1", 8, 30)!!.capSpeed(8.0)
        assertTrue(capped.all { it.speedKmh <= 8.0 })
    }

    @Test fun customProgramExpandsRepeats() {
        val p = CustomProgram("x", null, "Интервалы", listOf(
            Block(1, listOf(Segment(300, 4.0, 0.0))),
            Block(8, listOf(Segment(60, 10.0), Segment(120, 5.0))),
        ))
        assertEquals(1 + 16, p.segments().size)
        assertEquals(300 + 8 * 180, p.segments().sumOf { it.durationS })
    }

    @Test fun runnerWaitsForBeltThenStepsThroughSegmentsAndStops() {
        val r = ProgramRunner("t", "t", listOf(Segment(3, 4.0, 2.0), Segment(2, 6.0)))
        assertEquals(emptyList<Command>(), r.tick(Phase.COUNTDOWN, 1.0))
        assertEquals(listOf(Command.Speed(4.0), Command.Incline(2.0)), r.tick(Phase.RUNNING, 1.0))
        r.tick(Phase.RUNNING, 1.0)                                    // t=1
        r.tick(Phase.PAUSED, 1.0)                                     // пауза: время стоит
        r.tick(Phase.RUNNING, 1.0)                                    // t=2
        assertEquals(listOf(Command.Speed(6.0)), r.tick(Phase.RUNNING, 1.0)) // t=3 → второй отрезок, наклон не трогаем
        r.tick(Phase.RUNNING, 1.0)                                    // t=4
        assertEquals(listOf(Command.Stop), r.tick(Phase.RUNNING, 1.0))       // t=5 → конец
        assertEquals(RunState.DONE, r.state)
    }

    @Test fun runnerCancelsWhenStoppedFromConsole() {
        val r = ProgramRunner("t", "t", listOf(Segment(60, 4.0)))
        r.tick(Phase.RUNNING, 1.0)
        r.tick(Phase.STOPPING, 1.0)
        assertEquals(RunState.CANCELLED, r.state)
    }
}
