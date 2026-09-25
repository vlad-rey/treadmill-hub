package io.github.vladrey.treadmillhub

import io.github.vladrey.treadmillhub.gamification.OutMessage
import io.github.vladrey.treadmillhub.gamification.Telegram
import io.github.vladrey.treadmillhub.net.NetKind
import io.github.vladrey.treadmillhub.net.NetTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class NetTest {
    private val zone = ZoneId.of("Europe/Kyiv")
    private val t0 = LocalDateTime.of(2026, 9, 26, 12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun shortBlipIsIgnoredAndOutageNeedsThreeFails() {
        val n = NetTracker(confirm = 3, zone = zone)
        assertNull(n.onProbe(t0, true, false))
        assertNull(n.onProbe(t0 + 20_000, true, false))
        assertNull(n.outage)
        assertNull(n.onProbe(t0 + 40_000, true, true))          // два сбоя подряд — не отключение
        assertNull(n.outage)
    }

    @Test fun internetOutageThenRecoveryMessage() {
        val n = NetTracker(confirm = 3, zone = zone)
        for (i in 0..2) n.onProbe(t0 + i * 20_000L, true, false)
        assertEquals(NetKind.INTERNET, n.outage!!.kind)
        assertEquals(t0, n.outage!!.startMs)                   // начало — первая неудачная проверка
        val (o, text) = n.onProbe(t0 + 14 * 60_000L, true, true)!!
        assertEquals(t0 + 14 * 60_000L, o.endMs)
        assertTrue(text!!.startsWith("🌐 Интернет вернулся. Не было с 12:00 до 12:14 (14 мин)"))
        assertNull(n.outage)
    }

    @Test fun routerFailureMakesItRouterOutageAndShortOnesAreNotSent() {
        val n = NetTracker(confirm = 3, notifyMinMs = 60_000, zone = zone)
        n.onProbe(t0, true, false)
        n.onProbe(t0 + 10_000, false, false)
        n.onProbe(t0 + 20_000, true, false)
        assertEquals(NetKind.ROUTER, n.outage!!.kind)
        val r = n.onProbe(t0 + 30_000, true, true)!!
        assertEquals(NetKind.ROUTER, r.first.kind)
        assertNull(r.second)                                    // 30 с — в журнал, но без сообщения
        for (i in 0..3) n.onProbe(t0 + 100_000 + i * 20_000L, false, false)
        assertTrue(n.onProbe(t0 + 3_700_000, true, true)!!.second!!.contains("(1 ч 0 мин)"))
    }

    @Test fun delayedTelegramMessagesGetNote() {
        val t = Telegram({ null }, { null }, null, zone)
        val m = OutMessage(t0, "⚡ Свет выключили")
        assertEquals("⚡ Свет выключили", t.withDelayNote(m, t0 + 60_000))
        assertTrue(t.withDelayNote(m, t0 + 30 * 60_000).endsWith("написано в 12:00, не было связи с Telegram."))
        assertNotNull(t.withDelayNote(m, t0 + 86_400_000).let { if (it.contains("26.09 12:00")) it else null })
    }
}
