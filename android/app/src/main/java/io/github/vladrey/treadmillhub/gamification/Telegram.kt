package io.github.vladrey.treadmillhub.gamification

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class OutMessage(val atMs: Long, val text: String)

/**
 * Сообщения владельцу в Telegram. Токен и chat id — в настройках хаба, не в git.
 * Сообщения идут через очередь (сохраняется в [file]): если нет интернета — например, роутер
 * без света, — они уходят позже с пометкой, когда на самом деле случилось событие.
 */
class Telegram(
    private val token: () -> String?,
    private val chatId: () -> String?,
    private val file: File? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val queue = ArrayDeque<OutMessage>(
        runCatching { json.decodeFromString<List<OutMessage>>(file!!.readText()) }.getOrDefault(emptyList()),
    )
    private var worker: Job? = null

    /** Отправить сообщения, оставшиеся в очереди после перезапуска хаба. */
    fun start(scope: CoroutineScope) = synchronized(queue) { if (queue.isNotEmpty()) launchWorker(scope) }

    fun send(scope: CoroutineScope, text: String) = synchronized(queue) {
        queue.addLast(OutMessage(System.currentTimeMillis(), text))
        while (queue.size > MAX_QUEUE) queue.removeFirst()
        persist()
        launchWorker(scope)
    }

    private fun launchWorker(scope: CoroutineScope) {
        if (worker?.isActive == true) return
        worker = scope.launch(Dispatchers.IO) { drain() }
    }

    private suspend fun drain() {
        var wait = 15_000L
        while (true) {
            val m = synchronized(queue) { queue.firstOrNull() ?: run { worker = null; null } } ?: return
            val t = token() ?: return
            val c = chatId() ?: return
            val code = post(t, c, withDelayNote(m, System.currentTimeMillis()))
            if (code == 200 || code in 400..499 && code != 429) {
                // 4xx (кроме «слишком часто») не исправится повтором — не зацикливаемся
                if (code != 200) Log.w("Telegram", "sendMessage → $code, сообщение пропущено")
                synchronized(queue) { if (queue.firstOrNull() === m) queue.removeFirst(); persist() }
                wait = 15_000L
            } else {
                delay(wait)
                wait = (wait * 2).coerceAtMost(300_000L)
            }
        }
    }

    /** Код ответа или -1, если нет связи. */
    private fun post(t: String, c: String, text: String): Int = runCatching {
        val conn = URL("https://api.telegram.org/bot$t/sendMessage").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = "chat_id=${URLEncoder.encode(c, "UTF-8")}&text=${URLEncoder.encode(text, "UTF-8")}"
        conn.outputStream.use { it.write(body.toByteArray()) }
        conn.responseCode.also { conn.disconnect() }
    }.getOrElse { Log.w("Telegram", "не отправлено: ${it.message}"); -1 }

    fun withDelayNote(m: OutMessage, nowMs: Long): String {
        if (nowMs - m.atMs < DELAY_NOTE_MS) return m.text
        val at = Instant.ofEpochMilli(m.atMs).atZone(zone)
        val sameDay = at.toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val whenText = DateTimeFormatter.ofPattern(if (sameDay) "HH:mm" else "dd.MM HH:mm").format(at)
        return m.text + "\n\n⏱ Отправлено с задержкой: написано в $whenText, не было связи с Telegram."
    }

    private fun persist() {
        val f = file ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(queue.toList()))
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val MAX_QUEUE = 50
        const val DELAY_NOTE_MS = 120_000L
    }
}
