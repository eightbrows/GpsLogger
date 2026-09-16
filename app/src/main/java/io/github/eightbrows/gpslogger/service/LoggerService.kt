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
import io.github.eightbrows.gpslogger.state.BarometerReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.github.eightbrows.gpslogger.R
import io.github.eightbrows.gpslogger.BuildConfig
import io.github.eightbrows.gpslogger.session.Segment
import io.github.eightbrows.gpslogger.session.SessionMeta
import io.github.eightbrows.gpslogger.session.SessionReader
import io.github.eightbrows.gpslogger.calc.resolveBasePressureHpa

class LoggerService : Service() {

    private lateinit var locationManager: LocationManager

    /** LogWriterとセッションが生きているか。停止まで true（一時停止中も true） */
    private var sessionActive = false

    /** LocationManagerのコールバックが登録中か。一時停止で false */
    private var updatesActive = false

    /** 再開直後の最初の測位点に gap_before を立てるためのフラグ */
    private var pendingGapBefore = false

    /** stopLogging() を経て停止したか。異常破棄との区別に使う */
    private var stopRequested = false
    private var logWriter: LogWriter? = null
    private var satEpochId = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private var startTimeMs = 0L

    /** 記録開始時の設定間隔（秒）。記録中に設定が変わってもセッション内で固定する */
    private var sessionIntervalSec = 0
    private var fixCount = 0

    /** 記録中セッションのフォルダ（meta.json の書き出し先） */
    private var sessionDir: File? = null

    /** 確定済みの記録区間。一時停止で閉じ、再開で次の区間を開く */
    private val segments = mutableListOf<Segment>()
    private var segmentStartMs = 0L
    private var segmentPoints = 0
    private var segmentOpen = false
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
        // 再開直後の1点だけ true になる
        val gapBefore = pendingGapBefore
        pendingGapBefore = false
        // 測位が来た時点の最新値。CSVと軌跡に同じ値を積む（未取得なら null）
        val pressureHpa = BarometerReader.pressureHpa.value
        logWriter?.submit(
            LogEvent.Fix(
                location = location,
                dop = dop,
                gapBefore = gapBefore,
                intervalSec = sessionIntervalSec,
                pressureHpa = pressureHpa
            )
        )
        GnssStateHolder.updateLocation(location, gapBefore, pressureHpa)
        GnssStateHolder.updateDop(dop)
        fixCount++
        segmentPoints++
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
            ACTION_PAUSE -> pauseLogging()
            ACTION_RESUME -> resumeLogging()
            else -> {
                // 未知のアクション。startForegroundを呼ばないまま生き残らないようにする
                Log.w(TAG, "unknown action: ${intent?.action}")
                if (!sessionActive) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // 呼び出し側で位置許可を確認済み
    private fun startLogging() {
        if (sessionActive) return   // 記録中・一時停止中はどちらも無視する
        stopRequested = false
        pendingGapBefore = false

        try {
            startTimeMs = System.currentTimeMillis()
            fixCount = 0
            segments.clear()
            openSegment(startTimeMs)
            sessionIntervalSec = Settings.intervalSec.value
            GnssStateHolder.setRecordingStart(startTimeMs)

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
            this.sessionDir = sessionDir
            logWriter = LogWriter(sessionDir) { message ->
                GnssStateHolder.setLoggingError(message)
            }.also { it.start() }
            satEpochId = 0L
            sessionActive = true
            GnssStateHolder.reset()
            GnssStateHolder.setLogging(true)
            GnssStateHolder.setPaused(false)   // reset()では消えないので明示的に戻す
            // 気圧高度の基準気圧。記録中は meta.json を編集できないため、開始時点の値を使い続ける。
            // 新しいセッションには通常 meta.json が無いので標準大気になる
            GnssStateHolder.setBasePressure(
                resolveBasePressureHpa(SessionReader.readMeta(sessionDir), startTimeMs)
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return
        }

        try {
            startLocationUpdates()
            notificationHandler = Handler(mainLooper).also {
                it.postDelayed(notificationUpdater, 1000L)
            }
            acquireWakeLockIfEnabled()
            Log.d(TAG, "logging started")
        } catch (e: SecurityException) {
            Log.e(TAG, "location permission missing", e)
            stopLogging()
        }
    }

    /** 一時停止。測位だけ止め、ライターとセッションは保持する */
    private fun pauseLogging() {
        if (!sessionActive) {
            // サービスが動いていない状態での要求。通知を出さないまま残らないよう終了する
            Log.w(TAG, "pause requested while inactive")
            stopSelf()
            return
        }
        if (!updatesActive) return   // すでに一時停止中

        stopLocationUpdates()
        logWriter?.flushNow()         // しばらく放置されるのでバッファを確定させる
        releaseWakeLock()
        latestSats = emptyList()      // 通知に古い衛星数を残さない
        closeSegment(System.currentTimeMillis())
        GnssStateHolder.setPaused(true)
        updateNotification()
        Log.d(TAG, "logging paused")
    }

    /** 再開。同じセッション・同じライターに書き込みを続ける */
    private fun resumeLogging() {
        if (!sessionActive) {
            Log.w(TAG, "resume requested while inactive")
            stopSelf()
            return
        }
        if (updatesActive) return    // すでに記録中

        try {
            startLocationUpdates()
        } catch (e: SecurityException) {
            Log.e(TAG, "location permission missing", e)
            GnssStateHolder.setLoggingError("位置情報の権限がありません")
            stopLogging()
            return
        }
        pendingGapBefore = true      // 再開後の最初の1点に gap_before を立てる
        openSegment(System.currentTimeMillis())
        acquireWakeLockIfEnabled()
        GnssStateHolder.setPaused(false)
        updateNotification()
        Log.d(TAG, "logging resumed")
    }

    @SuppressLint("MissingPermission") // 呼び出し側で位置許可を確認済み
    private fun startLocationUpdates() {
        // 測位: GPS_PROVIDER、最小距離0m。間隔はセッション開始時の設定で固定
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            sessionIntervalSec * 1000L,
            0f,
            locationListener,
            mainLooper
        )
        // 衛星登録で失敗しても解除できるよう、ここで立てておく
        updatesActive = true
        // 衛星状態
        locationManager.registerGnssStatusCallback(
            gnssStatusCallback,
            Handler(mainLooper)
        )
        // 気圧も測位と同じ区間だけ購読する
        BarometerReader.start(this, owner = this)
    }

    private fun stopLocationUpdates() {
        if (!updatesActive) return
        locationManager.removeUpdates(locationListener)
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        BarometerReader.stop(owner = this)
        updatesActive = false
    }

    /**
     * WakeLockを取得する。タイムアウトは記録開始からの残り時間。
     * 再開のたびに上限12時間が延びるのを防ぐ。
     */
    private fun acquireWakeLockIfEnabled() {
        if (!Settings.useWakeLock.value || wakeLock != null) return
        val remaining = WAKELOCK_TIMEOUT_MS - (System.currentTimeMillis() - startTimeMs)
        if (remaining <= 0L) {
            Log.d(TAG, "wakelock budget exhausted; not acquiring")
            return
        }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GpsLogger::LoggingWakeLock"
        ).also { it.acquire(remaining) }
        Log.d(TAG, "wakelock acquired for ${remaining}ms")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * 測位コールバック・通知更新・WakeLock・ライターをまとめて片付ける。
     * stopLogging() と onDestroy() の共通処理。何度呼んでも安全。
     */
    private fun releaseResources() {
        stopLocationUpdates()
        notificationHandler?.removeCallbacks(notificationUpdater)
        notificationHandler = null
        releaseWakeLock()
        // ライターは updatesActive と切り離して必ず閉じる。
        // 一時停止中に停止しても未flush分を取りこぼさない
        logWriter?.stop()
        logWriter = null
        sessionActive = false
        pendingGapBefore = false
    }

    private fun stopLogging() {
        stopRequested = true
        val wasActive = sessionActive || logWriter != null
        val stopMs = System.currentTimeMillis()
        closeSegment(stopMs)   // 一時停止中なら閉じ済みなので何もしない
        releaseResources()     // ここでライターが閉じ、track.csv が確定する
        if (wasActive) {
            sessionDir?.let { writeMetaInBackground(it, stopMs) }
            sessionDir = null
            Log.d(TAG, "logging stopped")
            GnssStateHolder.setLogging(false)
            GnssStateHolder.setPaused(false)
            GnssStateHolder.setCurrentSession(null)
            GnssStateHolder.reset()
            GnssStateHolder.setRecordingStart(0L)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openSegment(startMs: Long) {
        segmentStartMs = startMs
        segmentPoints = 0
        segmentOpen = true
    }

    private fun closeSegment(endMs: Long) {
        if (!segmentOpen) return
        segments += Segment(
            start = SessionMeta.timeOf(segmentStartMs),
            end = SessionMeta.timeOf(endMs),
            points = segmentPoints,
            basePressureHpa = null
        )
        segmentOpen = false
    }

    /**
     * meta.json を書き出す。距離の計算で track.csv を全行読むため、メインスレッドでは行わない。
     * 値はスレッドへ渡す前に確定させる（直後に次の記録が始まっても混ざらない）。
     */
    private fun writeMetaInBackground(dir: File, endMs: Long) {
        val start = SessionMeta.timeOf(startTimeMs)
        val end = SessionMeta.timeOf(endMs)
        val points = fixCount
        val segs = segments.toList()
        val useWakeLock = Settings.useWakeLock.value

        Thread({
            try {
                SessionMeta(
                    start = start,
                    end = end,
                    pointCount = points,
                    distanceM = SessionReader.trackDistanceM(dir),
                    segments = segs,
                    useWakeLock = useWakeLock,
                    deviceModel = Build.MODEL.orEmpty(),
                    osVersion = Build.VERSION.RELEASE.orEmpty(),
                    appVersion = BuildConfig.VERSION_NAME
                ).writeTo(dir)
                Log.d(TAG, "meta.json written: ${dir.name}")
            } catch (e: Exception) {
                Log.e(TAG, "failed to write meta.json", e)
                GnssStateHolder.setLoggingError("記録情報（meta.json）の保存に失敗しました")
            }
        }, "MetaWriter").start()
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

        val paused = sessionActive && !updatesActive
        val usedSats = latestSats.count { it.usedInFix }
        val fixState = if (usedSats >= 4) "FIX" else "NO FIX"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                if (paused) "一時停止中  $elapsed" else "位置記録中  $elapsed"
            )
            .setContentText(
                if (paused) "${fixCount}点 ・ 測位停止中"
                else "$fixState ・ ${fixCount}点 ・ 衛星 $usedSats"
            )
            .setSmallIcon(R.drawable.ic_notification)
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
            "現在位置記録",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 想定外の破棄でも、コールバック解除とライターのクローズを確実に行う。
        // stopLogging() 経由なら片付け済みなので、ここは実質no-opになる。
        releaseResources()

        if (!stopRequested) {
            // stopLogging() を経ていない破棄（システムによる回収など）。
            // プロセスが生き残ると画面が「記録中」のまま固まるため、記録状態だけ戻す。
            // reset() は呼ばない（軌跡を消さずに残す）
            Log.w(TAG, "destroyed without stop request; clearing logging state")
            GnssStateHolder.setLogging(false)
            // 軌跡は残すので reset() は呼ばないが、以降のプレビューの点は標準大気に戻す
            GnssStateHolder.setBasePressure(BarometerReader.STANDARD_PRESSURE_HPA)
            GnssStateHolder.setPaused(false)
            GnssStateHolder.setCurrentSession(null)
            GnssStateHolder.setRecordingStart(0L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "LoggerService"
        const val ACTION_START = "io.github.eightbrows.gpslogger.START"
        const val ACTION_STOP = "io.github.eightbrows.gpslogger.STOP"
        const val ACTION_PAUSE = "io.github.eightbrows.gpslogger.PAUSE"
        const val ACTION_RESUME = "io.github.eightbrows.gpslogger.RESUME"
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

        fun pause(context: Context) {
            val intent = Intent(context, LoggerService::class.java).setAction(ACTION_PAUSE)
            context.startService(intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, LoggerService::class.java).setAction(ACTION_RESUME)
            context.startService(intent)
        }
    }
}