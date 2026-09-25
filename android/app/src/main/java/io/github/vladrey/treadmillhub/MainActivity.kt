package io.github.vladrey.treadmillhub

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

/** Экран на самом телефоне: только запуск сервиса и адрес веб-интерфейса. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, HubService::class.java))
        val port = HubConfig(this).port
        setContentView(TextView(this).apply {
            textSize = 20f
            setPadding(48, 96, 48, 48)
            text = "Хаб дорожки запущен.\n\nВеб-интерфейс: http://<IP телефона>:$port"
        })
    }
}
