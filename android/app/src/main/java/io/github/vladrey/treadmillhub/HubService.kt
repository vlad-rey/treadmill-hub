package io.github.vladrey.treadmillhub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Хаб как foreground service: держит BLE-соединение, сервер и wake lock.
 * При загрузке запускается из Magisk (MIUI не даёт автозапуск приложениям):
 *   am start-foreground-service -n io.github.vladrey.treadmillhub/.HubService
 */
class HubService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var hub: Hub? = null
    private var server: HubServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "treadmillhub:hub").apply { acquire() }
        // Wi-Fi не выключается при погасшем экране, но энергосбережение Wi-Fi разрешено:
        // HIGH_PERF давал заметный лишний расход, а задержка PS-режима (0,1–0,3 с) для 1 Гц незаметна
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL, "treadmillhub:wifi").apply { acquire() }

        val config = HubConfig(this)
        val h = Hub(this, config).also { it.start(scope) }
        hub = h
        server = HubServer(h, assets, config.port).also { it.start() }
        Log.i("HubService", "хаб запущен: backend=${config.backend}, порт ${config.port}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        server?.stop()
        hub?.close()
        scope.cancel()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("hub", "Хаб дорожки", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "hub")
            .setContentTitle("Хаб дорожки работает")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1, n)
    }
}
