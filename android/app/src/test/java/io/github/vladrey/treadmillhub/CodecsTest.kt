package io.github.vladrey.treadmillhub

import io.github.vladrey.treadmillhub.treadmill.FitShow
import io.github.vladrey.treadmillhub.treadmill.Ftms
import io.github.vladrey.treadmillhub.treadmill.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

/** Пакеты записаны с T12B 2026-09-25 (protocol/PROTOCOL.md). */
class CodecsTest {
    @Test fun treadmillDataIdle() {
        val d = Ftms.parseTreadmillData(hex("8c 05 00 00 00 00 00 00 00 00 00 00 00 ff ff ff 00 00 00"))!!
        assertEquals(0.0, d.speedKmh!!, 0.0)
        assertEquals(0, d.distanceM)
        assertEquals(0.0, d.inclinePct!!, 0.0)
        assertEquals(0, d.kcal)
        assertNull(d.heartRate)
        assertEquals(0, d.elapsedS)
    }

    @Test fun treadmillDataRunning() {
        // 3,0 км/ч, наклон 2 %, 1 ккал, 26 с
        val d = Ftms.parseTreadmillData(hex("8c 05 2c 01 00 00 00 14 00 00 00 01 00 ff ff ff 00 1a 00"))!!
        assertEquals(3.0, d.speedKmh!!, 1e-9)
        assertEquals(2.0, d.inclinePct!!, 1e-9)
        assertEquals(1, d.kcal)
        assertEquals(26, d.elapsedS)
    }

    @Test fun treadmillDataMoreDataPacketHasNoSpeed() {
        val d = Ftms.parseTreadmillData(hex("01 20 07 00 00"))!!
        assertNull(d.speedKmh)
    }

    @Test fun truncatedPacketIsRejected() {
        assertNull(Ftms.parseTreadmillData(hex("8c 05 00 00 00")))
    }

    @Test fun controlPointEncoding() {
        assertEquals("02 c8 00", Ftms.setSpeed(2.0).toHex())
        assertEquals("02 2c 01", Ftms.setSpeed(3.0).toHex())
        assertEquals("03 14 00", Ftms.setIncline(2.0).toHex())
        assertArrayEquals(hex("08 01"), Ftms.stop())
    }

    @Test fun controlPointResponse() {
        val r = Ftms.parseCpResponse(hex("80 07 01"))!!
        assertEquals(Ftms.OP_START, r.opcode)
        assertEquals(Ftms.RESULT_SUCCESS, r.result)
    }

    @Test fun fitShowIdle() {
        assertEquals(0, FitShow.parseStatus(hex("02 51 00 51 03"))!!.state)
    }

    @Test fun fitShowCountdown() {
        val s = FitShow.parseStatus(hex("02 51 02 03 50 03"))!!
        assertEquals(2, s.state)
        assertEquals(3, s.countdown)
    }

    @Test fun fitShowRunning() {
        val s = FitShow.parseStatus(hex("02 51 03 1e 00 22 00 00 00 11 00 07 00 00 00 78 03"))!!
        assertEquals(3, s.state)
        assertEquals(3.0, s.speedKmh!!, 1e-9)
        assertEquals(34, s.elapsedS)
        assertEquals(1.7, s.kcal!!, 1e-9)
    }

    @Test fun fitShowBadChecksum() {
        assertNull(FitShow.parseStatus(hex("02 51 00 52 03")))
    }

    @Test fun fitShowOtherCommandIsNotStatus() {
        assertNull(FitShow.parseStatus(hex("02 44 cb 8f 03")))
    }
}
