package io.github.eightbrows.gpslogger.settings

import io.github.eightbrows.gpslogger.R
import androidx.annotation.StringRes
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
    @param:StringRes val labelRes: Int,
    @param:StringRes val descriptionRes: Int,
    val manifestName: String,
    val minSdk: Int,
    /** 実行時ダイアログで要求できるか（不可ならシステム設定へ誘導） */
    val requestable: Boolean
) {
    FINE_LOCATION(
        R.string.perm_fine_location,
        R.string.perm_fine_location_desc,
        Manifest.permission.ACCESS_FINE_LOCATION,
        1,
        true
    ),
    BACKGROUND_LOCATION(
        R.string.perm_background_location,
        R.string.perm_background_location_desc,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Build.VERSION_CODES.Q,
        false
    ),
    NOTIFICATIONS(
        R.string.perm_notifications,
        R.string.perm_notifications_desc,
        "android.permission.POST_NOTIFICATIONS",
        Build.VERSION_CODES.TIRAMISU,
        true
    ),

    /**
     * 身体活動（歩数の試験機能）。任意の権限で、権限欄の行をタップしたときだけ要求する。
     * 記録の開始可否・初回の案内には含めない（[PermissionUtil.REQUIRED_FOR_LOGGING]）
     */
    ACTIVITY_RECOGNITION(
        R.string.perm_activity_recognition,
        R.string.perm_activity_recognition_desc,
        "android.permission.ACTIVITY_RECOGNITION",
        Build.VERSION_CODES.Q,
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

    /**
     * 記録に最低限必要な権限。記録の開始可否と、初回起動時の権限の案内に使う。
     * 任意の権限（身体活動など）は含めない
     */
    val REQUIRED_FOR_LOGGING: List<AppPermission> = listOf(AppPermission.FINE_LOCATION)

    /** 記録に最低限必要な権限が揃っているか */
    fun hasRequiredForLogging(context: Context): Boolean =
        REQUIRED_FOR_LOGGING.all { isGranted(context, it) }

    /** アプリのシステム設定画面を開く */
    fun openAppSettings(context: Context) {
        val intent = Intent(
            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}