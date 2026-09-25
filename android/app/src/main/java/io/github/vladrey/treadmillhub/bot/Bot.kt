package io.github.vladrey.treadmillhub.bot

import android.util.Log
import io.github.vladrey.treadmillhub.Hub
import io.github.vladrey.treadmillhub.gamification.Achievements
import io.github.vladrey.treadmillhub.gamification.Period
import io.github.vladrey.treadmillhub.gamification.Periods
import io.github.vladrey.treadmillhub.treadmill.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** Чат, которому владелец открыл доступ к боту; [profileId] — профиль дорожки (сводки и напоминания). */
@Serializable
data class BotChat(
    val chatId: String,
    val name: String,
    val username: String? = null,
    val profileId: String? = null,
    val allowed: Boolean = false,
    val requestedAtMs: Long = 0,
)

@Serializable
private data class BotFile(
    val chats: List<BotChat> = emptyList(),
    /** Расписание: задача → последний выполненный период (дата недели или дня). */
    val done: Map<String, String> = emptyMap(),
)

/**
 * Telegram-бот хаба: команды (/status, /progress, /week, /charge …), доступ по разрешению владельца,
 * недельные отчёты (пн 09:00) и напоминания о наградах (19:00). Владелец — чат из настроек хаба.
 */
class Bot(private val hub: Hub, dir: File, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val file = File(dir, "bot.json")
    private var data: BotFile = runCatching { json.decodeFromString<BotFile>(file.readText()) }.getOrDefault(BotFile())
    private val reports = Reports(zone)
    private val telegram get() = hub.telegram
    private lateinit var scope: CoroutineScope
    private var offset = 0L

    fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch(Dispatchers.IO) { poll() }
        scope.launch { while (isActive) { runCatching { schedule() }.onFailure { Log.w("Bot", "расписание: ${it.message}") }; delay(60_000) } }
    }

    @Synchronized fun chats(): List<BotChat> = data.chats

    // --- Приём команд (long polling) ---------------------------------------------------------
    private suspend fun poll() {
        var menuSet = false
        while (true) {
            if (telegram.ownerChatId == null) { delay(60_000); continue }
            if (!menuSet) menuSet = setMenu()
            val r = telegram.call("getUpdates", mapOf("offset" to "$offset", "timeout" to "50", "allowed_updates" to """["message"]"""), readTimeoutMs = 65_000)
            if (r == null || r.first != 200) { delay(if (r?.first == 409) 60_000 else 15_000); continue }
            val updates = runCatching { Json.parseToJsonElement(r.second).jsonObject["result"]!!.jsonArray }.getOrNull() ?: continue
            for (u in updates) {
                val o = u.jsonObject
                offset = maxOf(offset, (o["update_id"]?.jsonPrimitive?.long ?: continue) + 1)
                val m = o["message"]?.jsonObject ?: continue
                // Команды, пришедшие, пока хаб был выключен, старше 10 мин — не выполняем
                val date = m["date"]?.jsonPrimitive?.longOrNull ?: 0
                if (System.currentTimeMillis() / 1000 - date > 600) continue
                val chat = m["chat"]?.jsonObject ?: continue
                val from = m["from"]?.jsonObject
                val text = m["text"]?.jsonPrimitive?.content ?: continue
                runCatching {
                    handle(
                        chatId = chat["id"]!!.jsonPrimitive.content,
                        name = listOfNotNull(from.str("first_name"), from.str("last_name")).joinToString(" ").ifBlank { "Без имени" },
                        username = from.str("username"),
                        text = text.trim(),
                    )
                }.onFailure { Log.w("Bot", "команда «$text»: ${it.message}") }
            }
        }
    }

    private fun JsonObject?.str(key: String) = this?.get(key)?.jsonPrimitive?.content

    private fun setMenu(): Boolean {
        val cmds = listOf("status" to "Свет, станции, интернет, дорожка", "progress" to "Сколько осталось до наград",
            "week" to "Итоги текущей недели", "help" to "Что умеет бот")
        val arr = cmds.joinToString(",", "[", "]") { (c, d) -> """{"command":"$c","description":"$d"}""" }
        return telegram.call("setMyCommands", mapOf("commands" to arr))?.first == 200
    }

    private fun isOwner(chatId: String) = chatId == telegram.ownerChatId
    private fun chat(chatId: String) = synchronized(this) { data.chats.firstOrNull { it.chatId == chatId } }
    private fun allowed(chatId: String) = isOwner(chatId) || chat(chatId)?.allowed == true
    private fun reply(chatId: String, text: String) = telegram.sendTo(scope, chatId, text)

    private suspend fun handle(chatId: String, name: String, username: String?, text: String) {
        val parts = text.split(Regex("\\s+"))
        val cmd = parts[0].substringBefore('@').lowercase()
        val args = parts.drop(1)
        if (!allowed(chatId)) { requestAccess(chatId, name, username); return }
        val owner = isOwner(chatId)
        when (cmd) {
            "/start", "/help" -> reply(chatId, help(owner))
            "/status" -> reply(chatId, status())
            "/progress" -> reply(chatId, progress(chatId))
            "/week" -> reply(chatId, weekSoFar(chatId))
            "/charge", "/charge100", "/charge80" -> if (owner) charge(chatId, cmd, args) else reply(chatId, "Эта команда только для владельца.")
            "/speedtest" -> if (owner) speedtest(chatId) else reply(chatId, "Эта команда только для владельца.")
            "/allow" -> if (owner) allow(chatId, args) else reply(chatId, "Эта команда только для владельца.")
            "/deny" -> if (owner) deny(chatId, args) else reply(chatId, "Эта команда только для владельца.")
            "/me" -> if (owner) linkSelf(chatId, name, username, args) else reply(chatId, "Эта команда только для владельца.")
            "/chats" -> if (owner) reply(chatId, chatsText()) else reply(chatId, "Эта команда только для владельца.")
            else -> reply(chatId, "Не знаю такой команды. /help — список команд.")
        }
    }

    private fun help(owner: Boolean) = buildString {
        append("Команды:\n/status — свет, станции, интернет, дорожка\n/progress — сколько осталось до наград\n/week — итоги текущей недели")
        if (owner) append("\n\nВладельцу:\n/charge 100 — заряжать станции до 100 % (/charge 80, /charge 90 2 — только станция 2)\n/speedtest — замер скорости интернета роутером\n" +
            "/allow ID Имя — открыть доступ чату и привязать профиль дорожки\n/deny ID — закрыть доступ\n" +
            "/me Имя — привязать этот чат к своему профилю дорожки\n/chats — кто пользуется ботом")
        append("\n\nСами приходят: отчёт за неделю — в понедельник в 9:00, напоминания о наградах — в 19:00.")
    }

    private fun requestAccess(chatId: String, name: String, username: String?) {
        val now = System.currentTimeMillis()
        val prev = chat(chatId)
        if (prev != null && now - prev.requestedAtMs < 3600_000) return // не засыпаем владельца запросами
        update(BotChat(chatId, name, username, prev?.profileId, allowed = false, requestedAtMs = now))
        reply(chatId, "Привет! Это бот домашнего хаба. Запрос на доступ отправлен владельцу — после его подтверждения здесь появятся команды.")
        val profiles = hub.profiles.all().joinToString(", ") { it.name }
        telegram.send(scope, "👤 $name${username?.let { " (@$it)" } ?: ""} просит доступ к боту.\n" +
            "Разрешить и привязать профиль дорожки: /allow $chatId Имя (профили: $profiles)\nОтказать: /deny $chatId")
    }

    private fun allow(ownerChat: String, args: List<String>) {
        val id = args.firstOrNull() ?: return reply(ownerChat, "Формат: /allow ID Имя_профиля")
        val c = chat(id) ?: return reply(ownerChat, "Чат $id не просил доступ. Пусть напишет боту /start.")
        val profileName = args.drop(1).joinToString(" ")
        val profile = if (profileName.isBlank()) null else findProfile(profileName)
            ?: return reply(ownerChat, "Нет профиля «$profileName». Профили: ${hub.profiles.all().joinToString(", ") { it.name }}")
        update(c.copy(allowed = true, profileId = profile?.id ?: c.profileId))
        reply(ownerChat, "✅ Доступ для ${c.name} открыт" + (profile?.let { ", профиль дорожки «${it.name}»" } ?: "") + ".")
        reply(id, "✅ Доступ открыт!\n\n" + help(false))
    }

    private fun deny(ownerChat: String, args: List<String>) {
        val id = args.firstOrNull() ?: return reply(ownerChat, "Формат: /deny ID")
        val c = chat(id) ?: return reply(ownerChat, "Нет такого чата.")
        update(c.copy(allowed = false, requestedAtMs = System.currentTimeMillis()))
        reply(ownerChat, "Доступ для ${c.name} закрыт.")
    }

    private fun linkSelf(chatId: String, name: String, username: String?, args: List<String>) {
        val profileName = args.joinToString(" ")
        val p = findProfile(profileName)
            ?: return reply(chatId, "Формат: /me Имя_профиля. Профили: ${hub.profiles.all().joinToString(", ") { it.name }}")
        update(BotChat(chatId, name, username, p.id, allowed = true))
        reply(chatId, "Этот чат привязан к профилю «${p.name}»: сводки по дорожке и напоминания о наградах будут приходить сюда.")
    }

    /** Профиль по имени: точное совпадение или единственный, чьё имя начинается так («Диана» → «Диана <3»). */
    private fun findProfile(name: String) = name.trim().takeIf { it.isNotEmpty() }?.let { n ->
        hub.profiles.all().firstOrNull { it.name.equals(n, ignoreCase = true) }
            ?: hub.profiles.all().filter { it.name.startsWith(n, ignoreCase = true) }.singleOrNull()
    }

    private fun speedtest(chatId: String) {
        val started = hub.runSpeedTest { r ->
            reply(chatId, r.error?.let { "⚠️ Замер не удался: $it" }
                ?: "🚀 Скорость интернета (замер роутера): ${reports.num(r.downMbps ?: 0.0, 0)} ↓ / ${reports.num(r.upMbps ?: 0.0, 0)} ↑ Мбит/с, пинг ${r.pingMs ?: "?"} мс")
        }
        reply(chatId, if (started) "Запустил замер на роутере, это около минуты…" else "Не могу: " + if (hub.speed.running) "замер уже идёт." else "роутер не подключён.")
    }

    private fun chatsText(): String {
        val list = chats()
        if (list.isEmpty()) return "Кроме вас, ботом никто не пользуется."
        return list.joinToString("\n") { c ->
            val p = hub.profiles.get(c.profileId)?.name?.let { ", профиль «$it»" } ?: ""
            "${if (c.allowed) "✅" else "⏳"} ${c.name}${c.username?.let { " (@$it)" } ?: ""} — ${c.chatId}$p"
        }
    }

    private suspend fun charge(chatId: String, cmd: String, args: List<String>) {
        val pct = when (cmd) { "/charge100" -> 100; "/charge80" -> 80; else -> args.firstOrNull()?.toIntOrNull() }
        if (pct == null || pct !in 50..100 || pct % 5 != 0) return reply(chatId, "Формат: /charge 100 — от 50 до 100 с шагом 5. Можно указать номер станции: /charge 90 2")
        val configs = hub.power.configs()
        val which = (if (cmd == "/charge") args.getOrNull(1) else args.firstOrNull())?.toIntOrNull()
        val targets = if (which == null) configs else listOfNotNull(configs.getOrNull(which - 1))
        if (targets.isEmpty()) return reply(chatId, "Нет станции с таким номером. Станции: ${configs.mapIndexed { i, c -> "${i + 1} — ${c.name}" }.joinToString(", ")}")
        val lines = targets.map { c ->
            hub.power.writeSetting(c.id, "chargeLimit", pct * 10).fold({ "✅ ${c.name}: заряжать до $pct %" }, { "⚠️ ${c.name}: ${it.message}" })
        }
        reply(chatId, lines.joinToString("\n"))
    }

    // --- Ответы ------------------------------------------------------------------------------
    private fun status(): String {
        val snap = hub.snapshot.value
        val t = snap.treadmill
        val s = snap.session
        val treadmill = when {
            s.active -> "идёт тренировка" + (snap.ownerName?.let { " ($it)" } ?: "") +
                ", ${reports.num(t.speedKmh, 1)} км/ч, пройдено ${reports.num(s.distanceM / 1000, 2)} км"
            t.connection == Connection.CONNECTED -> "на связи, стоит"
            else -> "выключена"
        }
        val net = snap.hub.net
        return reports.status(
            System.currentTimeMillis(),
            hub.power.list().map { StationNow(it.name, it.state.connected, it.state.gridOn, it.state.socPct, it.state.outputW, it.state.acChargeW) },
            hub.power.outages.all().filter { it.endMs == null }.minOfOrNull { it.startMs },
            net.routerOk, net.internetOk, net.routerMs, treadmill, snap.hub.batteryPct, snap.hub.uptimeS,
        )
    }

    private fun progress(chatId: String): String {
        val pid = chat(chatId)?.profileId ?: return "Этот чат не привязан к профилю дорожки." +
            if (isOwner(chatId)) " Привяжите: /me Имя" else " Попросите владельца привязать."
        val name = hub.profiles.get(pid)?.name ?: "?"
        val rewards = hub.game.state(pid).rewards
        val today = LocalDate.now(zone)
        val weekKm = profileWeek(pid, today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today).km
        val head = "$name, на этой неделе пройдено ${reports.num(weekKm, 1)} км."
        if (rewards.isEmpty()) return head
        val left = reports.rewardReminder(name, rewards, today, force = true)
        val earned = rewards.filter { it.earned }.joinToString("\n") { "${it.def.icon} «${it.def.title}» — заработана ✅" }
        return listOfNotNull(head, left?.substringAfter("\n\n")?.substringBefore("\n\nВперёд"), earned.ifBlank { null }).joinToString("\n\n")
    }

    private fun weekSoFar(chatId: String): String {
        val today = LocalDate.now(zone)
        val from = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val pid = chat(chatId)?.profileId
        return if (isOwner(chatId)) homeWeek(from, today) + (pid?.let { "\n\n" + reports.treadmillWeek(from, today, profileWeek(it, from, today)) } ?: "")
        else pid?.let { reports.treadmillWeek(from, today, profileWeek(it, from, today)) } ?: "Этот чат не привязан к профилю дорожки."
    }

    // --- Данные для отчётов --------------------------------------------------------------------
    private fun ms(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()

    fun profileWeek(pid: String, from: LocalDate, to: LocalDate): ProfileWeek {
        val all = hub.history.list().filter { it.profileId == pid }
        val start = ms(from); val end = ms(to.plusDays(1))
        val week = all.filter { it.id in start until end }
        val prev = all.filter { it.id in ms(from.minusDays(7)) until start }
        val achievements = hub.game.store.achievements(pid).filter { it.atMs in start until end }
            .mapNotNull { e -> Achievements.all.firstOrNull { it.id == e.achievementId }?.let { "${it.icon} ${it.title}" } }
        val km = week.sumOf { it.distanceM } / 1000
        val key = Periods.key(Period.WEEK, start, zone)
        val rewards = hub.game.store.rewards(pid).filter { it.period == Period.WEEK }.map { d ->
            val earned = hub.game.store.rewardsEarned(pid).any { it.rewardId == d.id && it.periodKey == key }
            Triple("${d.icon} ${d.title}", earned, (d.km - km).coerceAtLeast(0.0))
        }
        return ProfileWeek(hub.profiles.get(pid)?.name ?: "?", km, week.sumOf { it.movingS }, week.size,
            week.sumOf { it.kcalCalc }, prev.sumOf { it.distanceM } / 1000, achievements, rewards)
    }

    fun homeWeek(from: LocalDate, to: LocalDate): String {
        val start = ms(from); val end = ms(to.plusDays(1))
        return reports.homeWeek(
            from, to,
            hub.power.configs().map { StationWeek(it.name, hub.power.statsStore.range(it.id, from, to)) },
            hub.power.outages.all().filter { it.startMs in start until end },
            hub.net.outages(500).filter { it.startMs in start until end },
            hub.speed.all().filter { it.atMs in start until end },
            hub.profiles.all().map { profileWeek(it.id, from, to) },
        )
    }

    // --- Расписание ----------------------------------------------------------------------------
    private fun schedule() {
        if (telegram.ownerChatId == null) return
        val now = ZonedDateTime.now(zone)
        val today = now.toLocalDate()

        // Отчёт за прошлую неделю: с понедельника 09:00 (если хаб был выключен — позже на этой неделе)
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekKey = monday.minusDays(7).toString()
        // Первый запуск: за прошлые недели данных нет — первый отчёт будет в следующий понедельник
        if (done("weekly") == null) markDone("weekly", weekKey)
        if (done("weekly") != weekKey && (today > monday || now.toLocalTime() >= LocalTime.of(9, 0))) {
            val from = monday.minusDays(7); val to = monday.minusDays(1)
            telegram.send(scope, homeWeek(from, to))
            val ownerProfile = chat(telegram.ownerChatId!!)?.profileId
            chats().filter { it.allowed && it.profileId != null }.forEach { c ->
                telegram.sendTo(scope, c.chatId, reports.treadmillWeek(from, to, profileWeek(c.profileId!!, from, to)))
            }
            if (ownerProfile == null) Log.i("Bot", "владелец не привязан к профилю — личная сводка не отправлена")
            markDone("weekly", weekKey)
        }

        // Напоминания о наградах: 19:00–22:00, раз в день
        val t = now.toLocalTime()
        if (done("remind") != today.toString() && t >= LocalTime.of(19, 0) && t < LocalTime.of(22, 0)) {
            chats().filter { it.allowed && it.profileId != null }.forEach { c ->
                val p = hub.profiles.get(c.profileId) ?: return@forEach
                reports.rewardReminder(p.name, hub.game.state(p.id).rewards, today)?.let { telegram.sendTo(scope, c.chatId, it) }
            }
            markDone("remind", today.toString())
        }
    }

    // --- Хранилище -----------------------------------------------------------------------------
    @Synchronized private fun done(key: String) = data.done[key]
    @Synchronized private fun markDone(key: String, value: String) { data = data.copy(done = data.done + (key to value)); save() }

    @Synchronized
    private fun update(c: BotChat) {
        data = data.copy(chats = data.chats.filter { it.chatId != c.chatId } + c)
        save()
    }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(data))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
