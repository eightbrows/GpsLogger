package io.github.eightbrows.gpslogger.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 身体活動の権限は任意で、記録の開始可否と初回の案内には含めない */
class PermissionUtilTest {

    @Test
    fun requiredForLogging_isLocationOnly() {
        assertEquals(listOf(AppPermission.FINE_LOCATION), PermissionUtil.REQUIRED_FOR_LOGGING)
        assertFalse(AppPermission.ACTIVITY_RECOGNITION in PermissionUtil.REQUIRED_FOR_LOGGING)
    }

    @Test
    fun activityRecognition_isListedForTheSettingsRow() {
        val p = AppPermission.ACTIVITY_RECOGNITION
        assertEquals("android.permission.ACTIVITY_RECOGNITION", p.manifestName)
        // Android 10（API 29）から実行時の許可が要る。それ未満では行を出さない
        assertEquals(29, p.minSdk)
        // 行をタップしたときに許可のダイアログを出せる
        assertTrue(p.requestable)
        assertTrue(p in AppPermission.entries)
    }
}
