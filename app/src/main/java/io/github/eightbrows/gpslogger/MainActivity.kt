package io.github.eightbrows.gpslogger

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.eightbrows.gpslogger.service.LoggerService
import io.github.eightbrows.gpslogger.ui.theme.GpsLoggerTheme

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.eightbrows.gpslogger.state.GnssStateHolder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import io.github.eightbrows.gpslogger.ui.BottomPager
import io.github.eightbrows.gpslogger.ui.SplitScreen
import io.github.eightbrows.gpslogger.ui.TrajectoryPane

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.eightbrows.gpslogger.session.SessionListScreen
import io.github.eightbrows.gpslogger.state.ViewSnapshot
import io.github.eightbrows.gpslogger.session.ReplayScreen
import io.github.eightbrows.gpslogger.session.ReplaySessionState
import androidx.compose.runtime.key
import io.github.eightbrows.gpslogger.settings.Settings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.unit.sp
import io.github.eightbrows.gpslogger.settings.SettingsScreen
import io.github.eightbrows.gpslogger.state.PreviewLocator
import io.github.eightbrows.gpslogger.state.BarometerReader
import androidx.compose.foundation.isSystemInDarkTheme
import io.github.eightbrows.gpslogger.settings.ThemeMode
import androidx.compose.foundation.background
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.LaunchedEffect
import io.github.eightbrows.gpslogger.settings.PermissionUtil
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.foundation.layout.navigationBarsPadding

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        // システムの「アプリの言語」から変えられた場合にも合わせる
        Settings.syncLanguageMode()
        setContent {
            val themeMode by Settings.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            GpsLoggerTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun RecordControlScreen() {
    val context = LocalContext.current
    val isLogging by GnssStateHolder.isLogging.collectAsState()
    val isPaused by GnssStateHolder.isPaused.collectAsState()
    val pressureHpa by BarometerReader.pressureHpa.collectAsState()
    val basePressureHpa by GnssStateHolder.basePressureHpa.collectAsState()
    val holderSnapshot by GnssStateHolder.snapshot.collectAsState()
    val trackPoints by GnssStateHolder.trackPoints.collectAsState()
    val currentSession by GnssStateHolder.currentSessionDir.collectAsState()
    val loggingError by GnssStateHolder.loggingError.collectAsState()

    val lastFixNs by GnssStateHolder.lastFixElapsedNs.collectAsState()
    val recordingStartMs by GnssStateHolder.recordingStartMs.collectAsState()
    var showPermissionRequired by remember { mutableStateOf(false) }

    // 最後の測位から5秒以内ならFIX中とみなす
    val hasFix = lastFixNs > 0 &&
            (android.os.SystemClock.elapsedRealtimeNanos() - lastFixNs) < 5_000_000_000L

    // ライブの状態を表示用スナップショットに詰め替える
    val viewSnapshot = ViewSnapshot(
        latitude = holderSnapshot.location?.latitude,
        longitude = holderSnapshot.location?.longitude,
        altitude = holderSnapshot.location?.altitude ?: 0.0,
        accuracy = holderSnapshot.location?.accuracy ?: 0f,
        verticalAccuracy = holderSnapshot.location?.verticalAccuracyMeters ?: 0f,
        speed = holderSnapshot.location?.speed ?: 0f,
        bearing = holderSnapshot.location?.bearing ?: 0f,
        bearingAccuracy = holderSnapshot.location?.bearingAccuracyDegrees ?: 0f,
        satellites = holderSnapshot.satellites,
        dop = holderSnapshot.dop,
        pressureHpa = pressureHpa,
        basePressureHpa = basePressureHpa,
        trackPoints = trackPoints,
        timeMs = holderSnapshot.location?.time ?: 0L,
        markerIndex = null,
        isRecording = isLogging,
        sessionStartMs = recordingStartMs,
        sessionEndMs = 0L
    )

    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { LoggerService.start(context) }

    val session = currentSession

    // 記録タブ表示中かつ記録していない間はプレビュー測位を動かす
    androidx.compose.runtime.DisposableEffect(isLogging) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!isLogging && hasPermission) {
            PreviewLocator.start(context)
        } else {
            PreviewLocator.stop()
        }

        onDispose { PreviewLocator.stop() }
    }

    // 気圧も PreviewLocator と同じく、記録していない間だけ画面側で購読する。
    // 記録中はサービスが購読し、一時停止中はどちらも購読しない（表示は「—」）
    androidx.compose.runtime.DisposableEffect(isLogging) {
        if (!isLogging) BarometerReader.start(context, owner = PreviewLocator)
        onDispose { BarometerReader.stop(owner = PreviewLocator) }
    }

    Column(Modifier.fillMaxSize()) {
        // 記録コントロール
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    when {
                        isPaused -> stringResource(R.string.status_paused)
                        isLogging -> stringResource(R.string.status_recording)
                        else -> stringResource(R.string.status_stopped)
                    },
                    fontSize = 13.sp
                )
                Text(
                    if (hasFix) "FIX" else "NO FIX",
                    fontSize = 13.sp,
                    color = if (hasFix) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
            // ボタン3つを狭い画面にも収めるため、左右の余白を詰める
            val compactPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        if (PermissionUtil.hasRequiredForLogging(context)) {
                            ensureNotificationThenStart(context, notificationPermissionLauncher)
                        } else {
                            showPermissionRequired = true
                        }
                    },
                    enabled = !isLogging,
                    contentPadding = compactPadding
                ) { Text(stringResource(R.string.record_start)) }

                Button(
                    onClick = {
                        if (isPaused) LoggerService.resume(context)
                        else LoggerService.pause(context)
                    },
                    enabled = isLogging,
                    contentPadding = compactPadding,
                    // 「一時停止」と「再開」で幅が変わらないよう固定する
                    modifier = Modifier.width(92.dp)
                ) { Text(stringResource(if (isPaused) R.string.record_resume else R.string.record_pause)) }

                Button(
                    onClick = { LoggerService.stop(context) },
                    enabled = isLogging,
                    contentPadding = compactPadding
                ) { Text(stringResource(R.string.record_stop)) }
            }
        }

        loggingError?.let { message ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    message,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { GnssStateHolder.setLoggingError(null) }) {
                    Text(stringResource(R.string.action_close), fontSize = 12.sp)
                }
            }
        }

        HorizontalDivider()

        // 上下分割
        SplitScreen(
            top = {
                TrajectoryPane(
                    viewSnapshot,
                    onClearTrack = if (!isLogging) {
                        { GnssStateHolder.clearTrackPoints() }
                    } else null
                )
            },
            bottom = { BottomPager(viewSnapshot) }
        )
    }

    if (showPermissionRequired) {
        AlertDialog(
            onDismissRequest = { showPermissionRequired = false },
            title = { Text(stringResource(R.string.permission_required_title)) },
            text = {
                Text(
                    stringResource(R.string.permission_required_message),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showPermissionRequired = false }) { Text("OK") }
            }
        )
    }
}


// 通知許可（Android 13+）を確認・要求してからサービスを起動するヘルパ
private fun ensureNotificationThenStart(
    context: android.content.Context,
    notificationLauncher: androidx.activity.result.ActivityResultLauncher<String>
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val notifGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (notifGranted) {
            LoggerService.start(context)
        } else {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    } else {
        LoggerService.start(context)
    }
}

private enum class Tab { RECORD, REPLAY, SETTINGS }

@Composable
fun AppRoot() {
    // 言語の切り替えで Activity が作り直されても、開いていたタブを保つ
    var tab by rememberSaveable { mutableStateOf(Tab.RECORD) }
    // 再生中のセッションと再生状態。タブを移っても破棄しないよう、画面の外（ここ）に持つ。
    // null なら履歴タブは一覧を出す
    var replay by remember { mutableStateOf<ReplaySessionState?>(null) }

    val context = LocalContext.current
    val noticeShown by Settings.permissionNoticeShown.collectAsState()
    var showPermissionNotice by remember { mutableStateOf(false) }

    // 初回起動時、権限が未取得なら一度だけ案内
    LaunchedEffect(Unit) {
        if (!noticeShown && !PermissionUtil.hasRequiredForLogging(context)) {
            showPermissionNotice = true
        }
    }

    // 再生画面を開いているときは一覧へ戻す（再生状態は捨てる）
    BackHandler(enabled = tab == Tab.REPLAY && replay != null) {
        replay = null
    }

    // 記録タブ以外にいるときは記録タブへ戻す（再生状態は保つ）
    BackHandler(enabled = tab != Tab.RECORD && !(tab == Tab.REPLAY && replay != null)) {
        tab = Tab.RECORD
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.RECORD -> RecordControlScreen()
                Tab.REPLAY -> {
                    val current = replay
                    if (current == null) {
                        // 選び直したセッションは新しい状態（先頭・等倍・1 ページ目）から始める
                        SessionListScreen(onSelect = { replay = ReplaySessionState(it) })
                    } else {
                        // key: 別のセッションに切り替わったら画面内の一時的な状態も作り直す
                        key(current) {
                            ReplayScreen(
                                state = current,
                                onBack = { replay = null }
                            )
                        }
                    }
                }
                Tab.SETTINGS -> SettingsScreen()
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .navigationBarsPadding()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val items = listOf(
                Triple(Tab.RECORD, Icons.Filled.Timeline, stringResource(R.string.tab_record)),
                Triple(Tab.REPLAY, Icons.Filled.History, stringResource(R.string.tab_history)),
                Triple(Tab.SETTINGS, Icons.Filled.Settings, stringResource(R.string.tab_settings))
            )
            items.forEach { (t, icon, label) ->
                val selected = tab == t
                val color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant

                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable {
                            // 履歴タブにいるときに履歴タブを押し直したら一覧へ戻る（これまでと同じ）。
                            // 他のタブから履歴タブへ移ったときは、再生していた画面をそのまま出す
                            if (t == Tab.REPLAY && tab == Tab.REPLAY) replay = null
                            tab = t
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(30.dp)
                    )
                    Text(
                        "  $label",
                        fontSize = 16.sp,
                        color = color
                    )
                }
            }
        }
    }

    if (showPermissionNotice) {
        AlertDialog(
            onDismissRequest = {
                showPermissionNotice = false
                Settings.setPermissionNoticeShown(true)
            },
            title = { Text(stringResource(R.string.permission_notice_title)) },
            text = {
                Text(
                    stringResource(R.string.permission_notice_message),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionNotice = false
                    Settings.setPermissionNoticeShown(true)
                    tab = Tab.SETTINGS
                }) { Text(stringResource(R.string.action_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPermissionNotice = false
                    Settings.setPermissionNoticeShown(true)
                }) { Text(stringResource(R.string.action_later)) }
            }
        )
    }
}