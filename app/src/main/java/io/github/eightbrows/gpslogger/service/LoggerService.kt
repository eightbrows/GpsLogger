package io.github.eightbrows.gpslogger.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.eightbrows.gpslogger.MainActivity
import io.github.eightbrows.gpslogger.calc.DopCalculator
import io.github.eightbrows.gpslogger.log.LogEvent
import io.github.eightbrows.gpslogger.log.LogWriter
import io.github.eightbrows.gpslogger.settings.Settings
import io.github.eightbrows.gpslogger.state.GnssStateHolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LoggerService : Service() {

    private lateinit var locationManager: LocationManager
    private var isLogging = false
    private var logWriter: LogWriter? = null
    private var satEpochId = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private var startTimeMs = 0L
    private var fixCount = 0
    private var notificationHandler: Handler? = null
    private val notificationUpdater = object : Runnable {
        override fun run() {
            updateNotification()
            notificationHandler?.postDelayed(this, 1000L)
        }
    }

    @Volatile private var latestSats: List<LogEvent.Sat> = emptyList()

    // 測位結果を受け取る
    private val locationListener = LocationListener { location ->
        val dop = DopCalculator.calculate(latestSats)
        logWriter?.submit(LogEvent.Fix(location, dop))
        GnssStateHolder.updateLocation(location)
        GnssStateHolder.updateDop(dop)
        fixCount++
    }

    // 衛星状態を受け取る
    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val sats = ArrayList<LogEvent.Sat>(status.satelliteCount)
            for (i in 0 until status.satelliteCount) {
                sats.add(
                    LogEvent.Sat(
                        constellation = status.getConstellationType(i),
                        svid = status.getSvid(i),
                        cn0DbHz = status.getCn0DbHz(i),
                        basebandCn0DbHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            status.getBasebandCn0DbHz(i) else 0f,
                        elevationDeg = status.getElevationDegrees(i),
                        azimuthDeg = status.getAzimuthDegrees(i),
                        carrierFrequencyHz = if (status.hasCarrierFrequencyHz(i))
                            status.getCarrierFrequencyHz(i) else 0f,
                        usedInFix = status.usedInFix(i),
                        hasAlmanac = status.hasAlmanacData(i),
                        hasEphemeris = status.hasEphemerisData(i)
                    )
                )
            }
            latestSats = sats
            val epochMs = System.currentTimeMillis()
            logWriter?.submit(
                LogEvent.Sats(
                    epochId = satEpochId++,
                    epochMs = epochMs,
                    elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                    satellites = sats
                )
            )
            GnssStateHolder.updateSatellites(sats, epochMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startLogging()
            ACTION_STOP -> stopLogging()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // 呼び出し側で位置許可を確認済み
    private fun startLogging() {
        if (isLogging) return

        try {
            startTimeMs = System.currentTimeMillis()
            fixCount = 0

            startForeground(NOTIFICATION_ID, buildNotification())

            // 保存先の確認
            val baseDir = getExternalFilesDir(null)
            if (baseDir == null) {
                Log.e(TAG, "external files dir unavailable")
                GnssStateHolder.setLoggingError("保存先が利用できません")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            // セッションフォルダを作成してライター開始
            val sessionName = "session_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(Date())
            val sessionDir = File(baseDir, sessionName)
            GnssStateHolder.setCurrentSession(sessionDir)
            logWriter = LogWriter(sessionDir) { message ->
                GnssStateHolder.setLoggingError(message)
            }.also { it.start() }
            satEpochId = 0L
            GnssStateHolder.reset()
            GnssStateHolder.setLogging(true)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return
        }

        try {
            // 測位: GPS_PROVIDER、最小間隔1秒・最小距離0m（v1既定）
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                Settings.intervalSec.value * 1000L,
                0f,
                locationListener,
                mainLooper
            )
            // 衛星状態
            locationManager.registerGnssStatusCallback(
                gnssStatusCallback,
                Handler(mainLooper)
            )
            isLogging = true
            notificationHandler = Handler(mainLooper).also {
                it.postDelayed(notificationUpdater, 1000L)
            }
            if (Settings.useWakeLock.value) {
                val pm = getSystemService(PowerManager::class.java)
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "GpsLogger::LoggingWakeLock"
                ).also { it.acquire(WAKELOCK_TIMEOUT_MS) }
                Log.d(TAG, "wakelock acquired")
            }
            Log.d(TAG, "logging started")
        } catch (e: SecurityException) {
            Log.e(TAG, "location permission missing", e)
            stopLogging()
        }
    }

    private fun stopLogging() {
        if (isLogging) {
            locationManager.removeUpdates(locationListener)
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
            notificationHandler?.removeCallbacks(notificationUpdater)
            notificationHandler = null
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            logWriter?.stop()
            logWriter = null
            isLogging = false
            Log.d(TAG, "logging stopped")
            GnssStateHolder.setLogging(false)
            GnssStateHolder.setCurrentSession(null)
            GnssStateHolder.reset()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        // タップでアプリに戻る
        val intent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )

        val elapsedSec = if (startTimeMs > 0)
            (System.currentTimeMillis() - startTimeMs) / 1000 else 0
        val h = elapsedSec / 3600
        val m = (elapsedSec % 3600) / 60
        val s = elapsedSec % 60
        val elapsed = "%02d:%02d:%02d".format(h, m, s)

        val usedSats = latestSats.count { it.usedInFix }
        val fixState = if (usedSats >= 4) "FIX" else "NO FIX"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS記録中  $elapsed")
            .setContentText("$fixState ・ ${fixCount}点 ・ 衛星 $usedSats")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "記録ステータス",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 想定外の破棄でもコールバックを確実に解除
        if (isLogging) {
            locationManager.removeUpdates(locationListener)
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
            notificationHandler?.removeCallbacks(notificationUpdater)
            notificationHandler = null
            isLogging = false
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "LoggerService"
        const val ACTION_START = "io.github.eightbrows.gpslogger.START"
        const val ACTION_STOP = "io.github.eightbrows.gpslogger.STOP"
        private const val CHANNEL_ID = "logging_status"
        private const val NOTIFICATION_ID = 1

        /** WakeLockの上限（12時間）。解放漏れ時の保険 */
        private const val WAKELOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L

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