package io.github.eightbrows.gpslogger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.eightbrows.gpslogger.session.SessionListScreen
import io.github.eightbrows.gpslogger.state.ViewSnapshot
import io.github.eightbrows.gpslogger.session.ReplayScreen
import io.github.eightbrows.gpslogger.settings.Settings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.unit.sp
import io.github.eightbrows.gpslogger.settings.SettingsScreen
import io.github.eightbrows.gpslogger.state.PreviewLocator
import androidx.compose.foundation.isSystemInDarkTheme
import io.github.eightbrows.gpslogger.settings.ThemeMode
import androidx.compose.foundation.background
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
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
    val holderSnapshot by GnssStateHolder.snapshot.collectAsState()
    val trackPoints by GnssStateHolder.trackPoints.collectAsState()
    val currentSession by GnssStateHolder.currentSessionDir.collectAsState()
    var reviewing by remember { mutableStateOf(false) }
    val loggingError by GnssStateHolder.loggingError.collectAsState()

    val lastFixNs by GnssStateHolder.lastFixElapsedNs.collectAsState()
    // 最後の測位から5秒以内ならFIX中とみなす
    val hasFix = lastFixNs > 0 &&
            (android.os.SystemClock.elapsedRealtimeNanos() - lastFixNs) < 5_000_000_000L

    // ライブの状態を表示用スナップショットに詰め替える
    val viewSnapshot = ViewSnapshot(
        latitude = holderSnapshot.location?.latitude,
        longitude = holderSnapshot.location?.longitude,
        altitude = holderSnapshot.location?.altitude ?: 0.0,
        accuracy = holderSnapshot.location?.accuracy ?: 0f,
        speed = holderSnapshot.location?.speed ?: 0f,
        bearing = holderSnapshot.location?.bearing ?: 0f,
        satellites = holderSnapshot.satellites,
        dop = holderSnapshot.dop,
        trackPoints = trackPoints,
        timeMs = holderSnapshot.location?.time ?: 0L,
        markerIndex = null,
        isRecording = isLogging
    )

    // 記録確認を開いているときはライブ表示へ戻す
    BackHandler(enabled = reviewing) {
        reviewing = false
    }

    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { LoggerService.start(context) }

    val locationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) ensureNotificationThenStart(context, notificationPermissionLauncher)
    }

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

    if (reviewing && session != null) {
        // レビュー中: 記録は裏で継続したまま、現セッションを再生画面で開く
        ReplayScreen(
            sessionDir = session,
            onBack = { reviewing = false }
        )
    } else {
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
                    Text(if (isLogging) "● 記録中" else "○ 停止中", fontSize = 13.sp)
                    Text(
                        if (hasFix) "FIX" else "NO FIX",
                        fontSize = 13.sp,
                        color = if (hasFix) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val fine = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                            if (fine) ensureNotificationThenStart(context, notificationPermissionLauncher)
                            else locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        },
                        enabled = !isLogging
                    ) { Text("開始") }

                    Button(
                        onClick = { LoggerService.stop(context) },
                        enabled = isLogging
                    ) { Text("停止") }

                    Button(
                        onClick = { reviewing = true },
                        enabled = isLogging && session != null
                    ) { Text("記録確認") }
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
                        Text("閉じる", fontSize = 12.sp)
                    }
                }
            }

            HorizontalDivider()

            // 上下分割
            SplitScreen(
                top = { TrajectoryPane(viewSnapshot) },
                bottom = { BottomPager(viewSnapshot) }
            )
        }
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
    var tab by remember { mutableStateOf(Tab.RECORD) }
    var selectedSession by remember { mutableStateOf<java.io.File?>(null) }

    // 再生画面を開いているときは一覧へ戻す
    BackHandler(enabled = tab == Tab.REPLAY && selectedSession != null) {
        selectedSession = null
    }

    // 記録タブ以外にいるときは記録タブへ戻す
    BackHandler(enabled = tab != Tab.RECORD && selectedSession == null) {
        tab = Tab.RECORD
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.RECORD -> RecordControlScreen()
                Tab.REPLAY -> {
                    val session = selectedSession
                    if (session == null) {
                        SessionListScreen(onSelect = { selectedSession = it })
                    } else {
                        ReplayScreen(
                            sessionDir = session,
                            onBack = { selectedSession = null }
                        )
                    }
                }
                Tab.SETTINGS -> SettingsScreen()
            }
        }

        NavigationBar {
            NavigationBarItem(
                selected = tab == Tab.RECORD,
                onClick = { tab = Tab.RECORD },
                icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                label = { Text("記録") }
            )
            NavigationBarItem(
                selected = tab == Tab.REPLAY,
                onClick = {
                    tab = Tab.REPLAY
                    selectedSession = null  // タブ切替で一覧に戻す
                },
                icon = { Icon(Icons.Filled.History, contentDescription = null) },
                label = { Text("再生") }
            )
            NavigationBarItem(
                selected = tab == Tab.SETTINGS,
                onClick = { tab = Tab.SETTINGS },
                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                label = { Text("設定") }
            )
        }
    }
}