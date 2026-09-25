package io.github.vladrey.treadmillhub

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

/** Screen on the phone itself: just starts the service and shows the web UI address. */
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
