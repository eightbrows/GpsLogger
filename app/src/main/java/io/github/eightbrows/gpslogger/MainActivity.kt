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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GpsLoggerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RecordControlScreen()
                }
            }
        }
    }
}

@Composable
fun RecordControlScreen() {
    val context = LocalContext.current

    // 通知許可の結果 → 許可有無に関わらず最終的にサービス起動（通知は33+のみ必須）
    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        LoggerService.start(context)
    }

    // 位置許可の結果 → 許可されたら通知許可の確認へ進む
    val locationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            ensureNotificationThenStart(context, notificationPermissionLauncher)
        }
        // 拒否時は今は何もしない（次段でUI表示を整える）
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("GPS Logger（骨組み確認）")

        Button(onClick = {
            val fineGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (fineGranted) {
                // 位置が既に許可済みなら通知許可の確認へ
                ensureNotificationThenStart(context, notificationPermissionLauncher)
            } else {
                // 位置許可（FINE/COARSE）をまとめてリクエスト
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }) {
            Text("記録開始")
        }

        Button(onClick = { LoggerService.stop(context) }) {
            Text("記録停止")
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