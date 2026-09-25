package io.github.vladrey.treadmillhub

import io.github.vladrey.treadmillhub.bot.ProfileWeek
import io.github.vladrey.treadmillhub.bot.Reports
import io.github.vladrey.treadmillhub.bot.StationNow
import io.github.vladrey.treadmillhub.bot.StationWeek
import io.github.vladrey.treadmillhub.gamification.Period
import io.github.vladrey.treadmillhub.gamification.RewardDef
import io.github.vladrey.treadmillhub.gamification.RewardStatus
import io.github.vladrey.treadmillhub.net.DevicePatch
import io.github.vladrey.treadmillhub.net.DeviceRegistry
import io.github.vladrey.treadmillhub.net.NetKind
import io.github.vladrey.treadmillhub.net.NetOutage
import io.github.vladrey.treadmillhub.net.SpeedResult
import io.github.vladrey.treadmillhub.power.Outage
import io.github.vladrey.treadmillhub.power.StationTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class BotTest {
    private val zone = ZoneId.of("Europe/Kyiv")
    private val r = Reports(zone)
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 9, d, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test fun pluralsAndTitles() {
        assertEquals("тренировка", r.plural(1, "тренировка", "тренировки", "тренировок"))
        assertEquals("тренировки", r.plural(3, "тренировка", "тренировки", "тренировок"))
        assertEquals("тренировок", r.plural(11, "тренировка", "тренировки", "тренировок"))
        assertEquals("тренировка", r.plural(21, "тренировка", "тренировки", "тренировок"))
        assertEquals("21–27 сентября", r.weekTitle(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27)))
        assertEquals("28 сентября – 4 октября", r.weekTitle(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4)))
    }

    @Test fun homeWeekMergesOutagesOfBothStations() {
        val outages = listOf(
            Outage("a", at(22, 14, 5), at(22, 16, 45)), Outage("b", at(22, 14, 6), at(22, 16, 44)), // одно отключение
            Outage("a", at(24, 20, 0), at(24, 20, 30)),
        )
        val text = r.homeWeek(
            LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27),
            listOf(StationWeek("Станция 1", StationTotals(chargedPct = 42.0, chargedWh = 900.0, outputWh = 3200.0, offgridOutputWh = 600.0))),
            outages,
            listOf(NetOutage(NetKind.INTERNET, at(23, 10), at(23, 10, 35))),
            listOf(SpeedResult(at(23, 7), 900.0, 450.0), SpeedResult(at(23, 21), 700.0, 430.0)),
            listOf(ProfileWeek("Диана", 7.2, 7500.0, 4, 540.0, 5.9, rewards = listOf(Triple("🍣 Суши", true, 0.0)))),
        )
        assertTrue(text, text.contains("⚡ Свет отключали 2 раза, всего 3 ч 10 мин (дольше всего — 2 ч 40 мин, вт 14:05)"))
        assertTrue(text, text.contains("• Станция 1: циклы 0,42 · заряжено 42 % (900 Вт·ч из сети) · из батареи 600 Вт·ч · всего отдано 3,20 кВт·ч"))
        assertTrue(text, text.contains("🌐 Интернет: 1 сбой, всего 35 мин (провайдер, роутер работал)"))
        assertTrue(text, text.contains("🚀 Скорость: в среднем 800 ↓ / 440 ↑ Мбит/с, минимум 700 ↓ (2 замера)"))
        assertTrue(text, text.contains("• Диана: 7,2 км за 4 тренировки (+1,3 км к прошлой неделе) · 🍣 Суши ✅"))
    }

    @Test fun treadmillWeekTexts() {
        val from = LocalDate.of(2026, 9, 21); val to = LocalDate.of(2026, 9, 27)
        val t = r.treadmillWeek(from, to, ProfileWeek("Диана", 7.2, 7500.0, 4, 540.4, 5.9, listOf("🥇 Марафонец"), listOf(Triple("🍣 Суши", false, 0.8))))
        assertTrue(t, t.contains("7,2 км · 4 тренировки · 2 ч 5 мин · 540 ккал"))
        assertTrue(t, t.contains("На 1,3 км больше, чем неделей раньше"))
        assertTrue(t, t.contains("🏆 Новые ачивки: 🥇 Марафонец"))
        assertTrue(t, t.contains("🍣 Суши — не хватило 0,8 км"))
        assertTrue(r.treadmillWeek(from, to, ProfileWeek("Влад", 0.0, 0.0, 0, 0.0, 0.0)).contains("Тренировок не было"))
    }

    @Test fun rewardRemindersOnlyOnDueDays() {
        val sushi = RewardDef("sushi", "p", "Суши", "🍣", Period.WEEK, 6.0)
        val doll = RewardDef("doll", "p", "Кукла", "🎎", Period.MONTH, 24.0)
        val list = listOf(RewardStatus(sushi, "w", 4.8, false, false, 0), RewardStatus(doll, "m", 13.5, false, false, 0))
        // Чт 24.09: недельная — да (до конца недели 3 дня), месячная — нет (осталось 6 дней)
        val thu = r.rewardReminder("Диана", list, LocalDate.of(2026, 9, 24))!!
        assertTrue(thu, thu.contains("🍣 «Суши»: осталось 1,2 км (пройдено 4,8 из 6), до конца недели 3 дня"))
        assertFalse(thu, thu.contains("Кукла"))
        assertNull(r.rewardReminder("Диана", list, LocalDate.of(2026, 9, 25)))           // пятница
        // 29.09 — до конца месяца 1 день; вторник — недельной нет
        val m = r.rewardReminder("Диана", list, LocalDate.of(2026, 9, 29))!!
        assertTrue(m, m.contains("🎎 «Кукла»: осталось 10,5 км (пройдено 13,5 из 24), до конца месяца 1 день"))
        // Заработанные не напоминаем
        assertNull(r.rewardReminder("Диана", listOf(RewardStatus(sushi, "w", 6.5, true, false, 1)), LocalDate.of(2026, 9, 24)))
        assertTrue(r.rewardReminder("Диана", list, LocalDate.of(2026, 9, 27), force = true)!!.contains("это последний день недели"))
    }

    @Test fun statusText() {
        val s = r.status(at(26, 12, 40), listOf(StationNow("Станция 1", true, false, 80.0, 400, 0), StationNow("Станция 2", false, null, null, 0, 0)),
            at(26, 12, 0), true, true, 4, "выключена", 78, 3 * 3600L)
        assertTrue(s, s.contains("⚡ Света нет с 12:00 (40 мин)."))
        assertTrue(s, s.contains("🔋 Станция 1: 80 %, от батареи 400 Вт — хватит примерно на 3,7 ч"))
        assertTrue(s, s.contains("🔋 Станция 2: нет связи"))
        assertTrue(s, s.contains("🌐 Интернет есть, роутер 4 мс"))
    }

    @Test fun deviceRegistryLearnsThenReportsNewDevices() {
        val file = File.createTempFile("devices", ".json").apply { delete() }
        val reg = DeviceRegistry(file, learnMs = 1000)
        assertTrue(reg.seen(listOf("192.168.50.1" to "04:BB:CC:00:00:01"), 0).isEmpty())       // обучение
        assertTrue(reg.seen(listOf("192.168.50.2" to "04:bb:cc:00:00:02"), 500).isEmpty())
        val fresh = reg.seen(listOf("192.168.50.1" to "04:bb:cc:00:00:01", "192.168.50.77" to "da:11:22:33:44:55"), 2000)
        assertEquals(listOf("da:11:22:33:44:55"), fresh.map { it.mac })
        assertTrue(fresh[0].randomMac)
        assertFalse(reg.all().first { it.mac == "04:bb:cc:00:00:01" }.randomMac)
        assertTrue(reg.seen(listOf("192.168.50.78" to "da:11:22:33:44:55"), 3000).isEmpty())  // второй раз — не новое
        reg.patch("DA:11:22:33:44:55", DevicePatch(name = "Телефон гостя", known = true))
        val d = DeviceRegistry(file).all().first { it.mac == "da:11:22:33:44:55" }
        assertEquals("Телефон гостя", d.name)
        assertEquals("192.168.50.78", d.ip)
        assertTrue(d.known)
        reg.patch("da:11:22:33:44:55", DevicePatch(known = false))
        assertEquals("Телефон гостя", reg.all().first { it.mac == "da:11:22:33:44:55" }.name) // имя не стёрлось
        file.delete()
    }

    @Test fun stationWifiIsNamedByBluetoothAddress() {
        assertEquals("e8:f6:0a:05:87:f4", DeviceRegistry.wifiMacOfBle("E8:F6:0A:05:87:F6"))
        assertEquals("14:c1:9f:a1:1b:fe", DeviceRegistry.wifiMacOfBle("14:C1:9F:A1:1C:00")) // перенос через байт
        val file = File.createTempFile("devices", ".json").apply { delete() }
        val reg = DeviceRegistry(file, learnMs = 0)
        reg.seen(listOf("192.168.50.18" to "e8:f6:0a:05:87:f4", "192.168.50.5" to "04:00:00:00:00:05"), 10)
        reg.nameStations(mapOf("e8:f6:0a:05:87:f6" to "Станция 1"))
        val st = reg.all().first { it.mac == "e8:f6:0a:05:87:f4" }
        assertEquals("⚡ Станция 1", st.name)
        assertTrue(st.known)
        assertNull(reg.all().first { it.mac == "04:00:00:00:00:05" }.name)
        file.delete()
    }

    @Test fun arpParsing() {
        val arp = """IP address       HW type     Flags       HW address            Mask     Device
192.168.50.1     0x1         0x2         04:42:1a:00:00:01     *        wlan0
192.168.50.33    0x1         0x0         00:00:00:00:00:00     *        wlan0
192.168.50.40    0x1         0x2         DA:11:22:33:44:55     *        wlan0
10.0.0.1         0x1         0x2         11:22:33:44:55:66     *        rmnet0"""
        assertEquals(listOf("192.168.50.1" to "04:42:1a:00:00:01", "192.168.50.40" to "da:11:22:33:44:55"), DeviceRegistry.parseArp(arp))
    }
}
