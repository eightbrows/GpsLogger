package io.github.eightbrows.gpslogger.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.ContextCompat

/** アプリが使う権限の種別 */
enum class AppPermission(
    val label: String,
    val description: String,
    val manifestName: String,
    val minSdk: Int,
    /** 実行時ダイアログで要求できるか（不可ならシステム設定へ誘導） */
    val requestable: Boolean
) {
    FINE_LOCATION(
        "位置情報（正確）",
        "GNSSによる測位に必要です",
        Manifest.permission.ACCESS_FINE_LOCATION,
        1,
        true
    ),
    BACKGROUND_LOCATION(
        "位置情報（常に許可）",
        "画面を閉じても記録を続けるために必要です",
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Build.VERSION_CODES.Q,
        false
    ),
    NOTIFICATIONS(
        "通知",
        "記録中であることを通知に表示します",
        "android.permission.POST_NOTIFICATIONS",
        Build.VERSION_CODES.TIRAMISU,
        true
    );

    /** この端末で必要な権限か */
    val isApplicable: Boolean get() = Build.VERSION.SDK_INT >= minSdk
}

object PermissionUtil {

    fun isGranted(context: Context, permission: AppPermission): Boolean {
        if (!permission.isApplicable) return true
        return ContextCompat.checkSelfPermission(context, permission.manifestName) ==
                PackageManager.PERMISSION_GRANTED
    }

    /** 記録に最低限必要な権限が揃っているか */
    fun hasRequiredForLogging(context: Context): Boolean =
        isGranted(context, AppPermission.FINE_LOCATION)

    /** アプリのシステム設定画面を開く */
    fun openAppSettings(context: Context) {
        val intent = Intent(
            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}