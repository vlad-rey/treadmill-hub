package io.github.vladrey.treadmillhub

import io.github.vladrey.treadmillhub.power.GridWatch
import io.github.vladrey.treadmillhub.power.StationCodec
import io.github.vladrey.treadmillhub.power.StationSettings
import io.github.vladrey.treadmillhub.power.StationState
import io.github.vladrey.treadmillhub.treadmill.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class PowerTest {
    @Test fun requestsHaveModbusCrcHighByteFirst() {
        // Этот запрос станция F2400 приняла при проверке с PC (2026-09-25)
        val r = StationCodec.read(StationCodec.READ_INPUT)
        assertEquals("11 04 00 00 00 50", r.copyOfRange(0, 6).toHex())
        val c = StationCodec.crc16(r.copyOfRange(0, 6))
        assertEquals(c shr 8 and 0xFF, r[6].toInt() and 0xFF)
        assertEquals(c and 0xFF, r[7].toInt() and 0xFF)
        assertEquals("11 06 00 38 00 01", StationCodec.writeOne(56, 1).copyOfRange(0, 6).toHex())
    }

    @Test fun statusParsingFromRealRegisters() {
        val regs = IntArray(80).apply { this[6] = 196; this[20] = 196; this[21] = 2133; this[22] = 5002; this[56] = 956 }
        val s = StationCodec.parseStatus(regs, 1000, StationState())
        assertTrue(s.gridOn!!)
        assertEquals(213.3, s.gridVoltage!!, 1e-9)
        assertEquals(95.6, s.socPct!!, 1e-9)
        val off = StationCodec.parseStatus(IntArray(80).apply { this[20] = 180; this[56] = 900 }, 2000, s)
        assertFalse(off.gridOn!!)
        assertNull(off.gridVoltage)
    }

    @Test fun dangerousRegistersAreNotWritable() {
        assertNull(StationSettings.all.firstOrNull { it.reg == StationSettings.AUTO_OFF_REG })
        assertFalse(StationSettings.byKey("chargeLimit")!!.allowed(300))
        assertTrue(StationSettings.byKey("chargeLimit")!!.allowed(800))
        assertFalse(StationSettings.byKey("acStandby")!!.allowed(123))
    }

    @Test fun gridWatchDebouncesAndReportsOffOnAndLowBattery() {
        val w = GridWatch(confirmMs = 20_000, zone = ZoneId.of("Europe/Kyiv"))
        fun st(t: Long, grid: Boolean, soc: Double = 90.0) = StationState(connected = true, gridOn = grid, socPct = soc, outputW = 200, acChargeW = if (grid) 800 else 0, updatedAtMs = t)
        assertNull(w.update(st(0, true)))                 // первое состояние — без сообщения
        assertNull(w.update(st(10_000, false)))           // провал 10 с — ещё рано
        assertNull(w.update(st(15_000, true)))            // вернулся — ложная тревога
        assertNull(w.update(st(30_000, false)))
        val off = w.update(st(50_000, false))!!           // 20 с без света
        assertTrue(off.startsWith("⚡ Свет выключили"))
        assertNull(w.update(st(60_000, false)))           // повторно не шлём
        assertTrue(w.update(st(70_000, false, soc = 19.0))!!.startsWith("🪫"))
        assertNull(w.update(st(80_000, false, soc = 18.0)))
        assertNull(w.update(st(3_600_000, true)))
        val on = w.update(st(3_620_000, true))!!
        assertTrue(on.startsWith("💡 Свет включили"))
        assertTrue(on.contains("не было 59 мин"))
        assertTrue(on.contains("800 Вт"))
    }
}
