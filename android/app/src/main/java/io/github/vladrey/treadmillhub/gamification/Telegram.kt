package io.github.vladrey.treadmillhub.gamification

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Сообщения владельцу в Telegram (реальные награды). Токен и chat id — в настройках хаба, не в git. */
class Telegram(private val token: () -> String?, private val chatId: () -> String?) {
    fun send(scope: CoroutineScope, text: String) {
        val t = token() ?: return
        val c = chatId() ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val conn = URL("https://api.telegram.org/bot$t/sendMessage").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val body = "chat_id=${URLEncoder.encode(c, "UTF-8")}&text=${URLEncoder.encode(text, "UTF-8")}"
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                if (code != 200) Log.w("Telegram", "sendMessage → $code")
                conn.disconnect()
            }.onFailure { Log.w("Telegram", "не отправлено: ${it.message}") }
        }
    }
}
