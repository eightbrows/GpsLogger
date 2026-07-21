package io.github.eightbrows.gpslogger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class LoggerService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startLogging()
            ACTION_STOP -> stopLogging()
        }
        // 強制終了後にシステムが自動再起動しない（記録は明示開始のみ）
        return START_NOT_STICKY
    }

    private fun startLogging() {
        startForeground(NOTIFICATION_ID, buildNotification())
        // TODO: 測位・衛星コールバックの登録、ライター開始（次段で実装）
    }

    private fun stopLogging() {
        // TODO: コールバック解除、ライターの flush + close（次段で実装）
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS記録中")
            .setContentText("記録を開始しました")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation) // 仮アイコン。後で差し替え
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "記録ステータス",
            NotificationManager.IMPORTANCE_LOW // 音を鳴らさない
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "io.github.eightbrows.gpslogger.START"
        const val ACTION_STOP = "io.github.eightbrows.gpslogger.STOP"
        private const val CHANNEL_ID = "logging_status"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, LoggerService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LoggerService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}