package io.github.eightbrows.gpslogger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eightbrows.gpslogger.R
import io.github.eightbrows.gpslogger.calc.PressureUnits
import io.github.eightbrows.gpslogger.session.SessionMeta
import java.util.Locale
import kotlin.math.abs

/**
 * 設定画面と meta.json 編集画面で共通に使う入力ダイアログ。
 *
 * どのダイアログも、保存できない入力のときは保存ボタンを押せないようにする。
 * allowUnset が true のときは「未設定にする」も選べ、そのとき onSave には null を渡す
 * （meta.json 側で「上位の設定に従う」を表す）。
 */

/** 値の表示形式（端末の言語設定に左右されないよう Locale.ROOT で固定） */
fun formatGeoid(m: Double): String = String.format(Locale.ROOT, "%.1f", m)
fun formatHpa(hpa: Double): String = String.format(Locale.ROOT, "%.2f", hpa)
fun formatInHg(inHg: Double): String = String.format(Locale.ROOT, "%.2f", inHg)

/** 気圧の入力単位 */
private enum class PressureUnit(val label: String) { HPA("hPa"), INHG("inHg") }

/**
 * 基準気圧の直接入力。hPa と inHg（水銀柱インチ）を切り替えて入力できる。
 * 範囲外・数値でない入力は保存させない
 */
@Composable
fun BasePressureDialog(
    initialHpa: Double?,
    onDismiss: () -> Unit,
    onSave: (Double?) -> Unit,
    allowUnset: Boolean = false,
    unsetNote: String? = null
) {
    val start = initialHpa ?: SessionMeta.DEFAULT_BASE_PRESSURE_HPA
    var unit by remember { mutableStateOf(PressureUnit.HPA) }
    var text by remember { mutableStateOf(if (initialHpa == null) "" else formatHpa(initialHpa)) }

    // 小数点にカンマを使う言語のキーボードでも受け付ける
    val entered = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    val hpa = entered?.let { if (unit == PressureUnit.INHG) PressureUnits.hpaFromInHg(it) else it }
        ?.takeIf { it >= SessionMeta.MIN_BASE_PRESSURE_HPA && it <= SessionMeta.MAX_BASE_PRESSURE_HPA }

    ValueDialog(
        title = stringResource(R.string.settings_base_pressure),
        onDismiss = onDismiss,
        saveEnabled = hpa != null,
        onSaveClick = { hpa?.let(onSave) },
        allowUnset = allowUnset,
        unsetNote = unsetNote,
        onUnset = { onSave(null) }
    ) {
        SegmentedControl(
            options = PressureUnit.entries,
            selected = unit,
            label = { it.label },
            onSelect = { next ->
                // 単位を変えたら、今の入力値を同じ気圧のまま新しい単位へ書き換える
                if (next != unit) {
                    val current = hpa ?: start
                    text = if (next == PressureUnit.INHG) {
                        formatInHg(PressureUnits.inHgFromHpa(current))
                    } else {
                        formatHpa(current)
                    }
                    unit = next
                }
            }
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            singleLine = true,
            isError = hpa == null,
            suffix = { Text(unit.label) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = {
                Text(
                    if (unit == PressureUnit.INHG) {
                        stringResource(
                            R.string.settings_base_pressure_hint_inhg,
                            formatInHg(PressureUnits.inHgFromHpa(SessionMeta.MIN_BASE_PRESSURE_HPA)),
                            formatInHg(PressureUnits.inHgFromHpa(SessionMeta.MAX_BASE_PRESSURE_HPA)),
                            hpa?.let(::formatHpa) ?: "—"
                        )
                    } else {
                        stringResource(
                            R.string.settings_base_pressure_hint_hpa,
                            formatHpa(SessionMeta.MIN_BASE_PRESSURE_HPA),
                            formatHpa(SessionMeta.MAX_BASE_PRESSURE_HPA),
                            formatHpa(SessionMeta.DEFAULT_BASE_PRESSURE_HPA)
                        )
                    }
                )
            }
        )
    }
}

/** ジオイド高の直接入力。範囲外・数値でない入力は保存させない */
@Composable
fun GeoidOffsetDialog(
    initialM: Double?,
    onDismiss: () -> Unit,
    onSave: (Double?) -> Unit,
    allowUnset: Boolean = false,
    unsetNote: String? = null
) {
    var text by remember { mutableStateOf(if (initialM == null) "" else formatGeoid(initialM)) }
    // 小数点にカンマを使う言語のキーボードでも受け付ける
    val value = text.trim().replace(',', '.').toDoubleOrNull()
        ?.takeIf { it.isFinite() && abs(it) <= SessionMeta.GEOID_LIMIT_M }

    ValueDialog(
        title = stringResource(R.string.settings_geoid_height),
        onDismiss = onDismiss,
        saveEnabled = value != null,
        onSaveClick = { value?.let(onSave) },
        allowUnset = allowUnset,
        unsetNote = unsetNote,
        onUnset = { onSave(null) }
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = value == null,
            suffix = { Text("m") },
            // 負の値も入力するので、記号を打てるキーボードにする
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                Text(
                    stringResource(
                        R.string.settings_geoid_height_hint,
                        formatGeoid(-SessionMeta.GEOID_LIMIT_M),
                        formatGeoid(SessionMeta.GEOID_LIMIT_M),
                        formatGeoid(SessionMeta.DEFAULT_GEOID_OFFSET_M)
                    )
                )
            }
        )
    }
}

/** うるう秒の直接入力。整数のみで、範囲外の値は保存させない */
@Composable
fun LeapSecondsDialog(
    initial: Int?,
    onDismiss: () -> Unit,
    onSave: (Int?) -> Unit,
    allowUnset: Boolean = false,
    unsetNote: String? = null
) {
    var text by remember { mutableStateOf(initial?.toString() ?: "") }
    val value = text.trim().toIntOrNull()
        ?.takeIf { it in SessionMeta.MIN_LEAP_SECONDS..SessionMeta.MAX_LEAP_SECONDS }

    ValueDialog(
        title = stringResource(R.string.settings_leap_seconds),
        onDismiss = onDismiss,
        saveEnabled = value != null,
        onSaveClick = { value?.let(onSave) },
        allowUnset = allowUnset,
        unsetNote = unsetNote,
        onUnset = { onSave(null) }
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = value == null,
            suffix = { Text(stringResource(R.string.unit_second)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                Text(
                    stringResource(
                        R.string.settings_leap_seconds_hint,
                        SessionMeta.MIN_LEAP_SECONDS,
                        SessionMeta.MAX_LEAP_SECONDS,
                        SessionMeta.DEFAULT_LEAP_SECONDS
                    )
                )
            }
        )
    }
}

/** 3 つのダイアログで共通の枠。入力欄の中身だけ content で差し替える */
@Composable
private fun ValueDialog(
    title: String,
    onDismiss: () -> Unit,
    saveEnabled: Boolean,
    onSaveClick: () -> Unit,
    allowUnset: Boolean,
    unsetNote: String?,
    onUnset: () -> Unit,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column {
                content()
                if (allowUnset) {
                    TextButton(onClick = onUnset, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.meta_clear_value), fontSize = 13.sp)
                    }
                    if (unsetNote != null) {
                        Text(
                            unsetNote,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = saveEnabled, onClick = onSaveClick) {
                Text(stringResource(R.string.meta_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** N択の排他選択を横並びの帯（セグメントコントロール）で表す */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .border(0.5.dp, MaterialTheme.colorScheme.outline, shape)
    ) {
        options.forEachIndexed { index, option ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )
            }
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(enabled = enabled) { onSelect(option) }
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(option),
                    fontSize = 12.sp,
                    color = when {
                        isSelected -> MaterialTheme.colorScheme.onPrimary
                        !enabled -> MaterialTheme.colorScheme.outline
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
