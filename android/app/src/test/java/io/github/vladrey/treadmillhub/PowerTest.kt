package io.github.vladrey.treadmillhub

import io.github.vladrey.treadmillhub.power.GridWatch
import io.github.vladrey.treadmillhub.power.OutageLog
import io.github.vladrey.treadmillhub.power.StationCodec
import io.github.vladrey.treadmillhub.power.StationSettings
import io.github.vladrey.treadmillhub.power.StationState
import io.github.vladrey.treadmillhub.power.StationStatsStore
import io.github.vladrey.treadmillhub.power.StationStatsTracker
import io.github.vladrey.treadmillhub.power.StationTotals
import io.github.vladrey.treadmillhub.treadmill.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
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

    @Test fun statsCountPercentEnergyChargeSessionsAndOutages() {
        val zone = ZoneId.of("Europe/Kyiv")
        val file = File.createTempFile("stats", ".json").apply { delete() }
        val store = StationStatsStore(file, zone)
        val t = StationStatsTracker("a", store)
        val t0 = LocalDateTime.of(2026, 9, 21, 10, 0).atZone(zone).toInstant().toEpochMilli() // понедельник
        fun st(s: Int, soc: Double, charge: Int, out: Int) =
            StationState(connected = true, socPct = soc, acChargeW = charge, outputW = out, updatedAtMs = t0 + s * 10_000L)
        // 6 опросов зарядки по 360 Вт: 50 → 52,5 %
        for (i in 0..5) t.onState(st(i, 50 + i * 0.5, 360, 0), gridOn = true, outageStarted = false)
        // Свет пропал: 3 опроса разрядки по 720 Вт
        t.onState(st(6, 52.0, 0, 720), gridOn = false, outageStarted = true)
        t.onState(st(7, 51.0, 0, 720), gridOn = false, outageStarted = false)
        t.onState(st(8, 50.5, 0, 720), gridOn = false, outageStarted = false)
        t.onState(st(8, 50.5, 0, 720), gridOn = false, outageStarted = false) // тот же опрос — не считается дважды
        // Свет вернулся, зарядка снова
        t.onState(st(9, 50.5, 400, 0), gridOn = true, outageStarted = false)
        t.onState(st(10, 51.0, 400, 0), gridOn = true, outageStarted = false)

        val p = store.periods("a", t0)
        with(p.today) {
            assertEquals(2, chargeSessions)
            assertEquals(3.0, chargedPct, 1e-9)            // 2,5 + 0,5
            assertEquals(2.0, dischargedPct, 1e-9)         // 52,5 → 50,5
            assertEquals(1, outages)
            assertEquals(30.0, outageS, 1e-9)              // опросы 6, 7, 8
            assertEquals(360 * 50 / 3600.0 + 400 * 20 / 3600.0, chargedWh, 1e-9)
            assertEquals(720 * 30 / 3600.0, offgridOutputWh, 1e-9)
            assertEquals(0.03, fullCycles, 1e-9)
        }
        assertEquals(p.today, p.all)
        assertEquals("2026-09-21", p.sinceDate)

        // Сохранение, перечитывание и границы периодов
        store.add("a", t0 - 86_400_000L, StationTotals(outages = 1))            // воскресенье — прошлая неделя
        store.add("a", LocalDateTime.of(2026, 6, 30, 12, 0).atZone(zone).toInstant().toEpochMilli(), StationTotals(outages = 1)) // прошлый квартал
        store.flush()
        val p2 = StationStatsStore(file, zone).periods("a", t0)
        assertEquals(1, p2.week.outages)
        assertEquals(2, p2.month.outages)
        assertEquals(2, p2.quarter.outages)
        assertEquals(3, p2.year.outages)
        assertEquals(3, p2.all.outages)
        file.delete()
    }

    @Test fun outageLogRecordsSocEnergyAndSurvivesReload() {
        val file = File.createTempFile("outages", ".json").apply { delete() }
        val log = OutageLog(file)
        fun st(t: Long, soc: Double, out: Int) = StationState(connected = true, socPct = soc, outputW = out, updatedAtMs = t)
        log.start("a", 1_000, 80.0)
        log.start("a", 5_000, 79.0)                              // повторное начало не создаёт вторую запись
        log.update("a", st(10_000, 79.0, 360))
        log.update("a", st(20_000, 78.0, 720))
        log.update("a", st(30_000, 77.5, 360))
        log.end("a", 40_000, 78.0)
        val o = OutageLog(file).all().single()
        assertEquals(1_000, o.startMs)
        assertEquals(40_000L, o.endMs)
        assertEquals(80.0, o.socStart!!, 1e-9)
        assertEquals(77.5, o.minSoc!!, 1e-9)
        assertEquals(78.0, o.socEnd!!, 1e-9)
        assertEquals((720 * 10 + 360 * 10) / 3600.0, o.batteryWh, 1e-9)
        assertEquals(720, o.maxOutputW)
        assertNull(OutageLog(file).open("a"))
        file.delete()
    }
}
