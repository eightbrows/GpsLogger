package io.github.eightbrows.gpslogger.session

import io.github.eightbrows.gpslogger.calc.StepSensorSpec
import io.github.eightbrows.gpslogger.calc.Steps
import io.github.eightbrows.gpslogger.calc.StepsStatus
import org.json.JSONObject

/** meta.json の steps.detector / steps.counter。センサーが無ければ present = false で、諸元は null */
data class StepSensorMeta(
    val present: Boolean = false,
    /** この記録で購読したか */
    val used: Boolean = false,
    val name: String? = null,
    val vendor: String? = null,
    val version: Int? = null,
    val wakeUp: Boolean? = null,
    val reportingMode: Int? = null,
    val minDelayUs: Int? = null,
    val maxDelayUs: Int? = null,
    val fifoReservedEventCount: Int? = null,
    val fifoMaxEventCount: Int? = null,
    /** steps.csv に書いた件数 */
    val events: Long = 0,
    /** そのうち時刻基準の異常（受け取りの遅れが負、または 60 秒超）の件数 */
    val timebaseAnomalies: Long = 0
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("present", present)
        put("used", used)
        put("name", name ?: JSONObject.NULL)
        put("vendor", vendor ?: JSONObject.NULL)
        put("version", version ?: JSONObject.NULL)
        put("wakeUp", wakeUp ?: JSONObject.NULL)
        put("reportingMode", reportingMode ?: JSONObject.NULL)
        put("minDelayUs", minDelayUs ?: JSONObject.NULL)
        put("maxDelayUs", maxDelayUs ?: JSONObject.NULL)
        put("fifoReservedEventCount", fifoReservedEventCount ?: JSONObject.NULL)
        put("fifoMaxEventCount", fifoMaxEventCount ?: JSONObject.NULL)
        put("events", events)
        put("timebaseAnomalies", timebaseAnomalies)
    }

    companion object {
        /** センサーの諸元と、記録した件数から作る。[spec] が null ならセンサー無し */
        fun of(spec: StepSensorSpec?, used: Boolean, events: Long, timebaseAnomalies: Long) =
            if (spec == null) {
                StepSensorMeta(present = false, used = false)
            } else {
                StepSensorMeta(
                    present = true,
                    used = used,
                    name = spec.name,
                    vendor = spec.vendor,
                    version = spec.version,
                    wakeUp = spec.wakeUp,
                    reportingMode = spec.reportingMode,
                    minDelayUs = spec.minDelayUs,
                    maxDelayUs = spec.maxDelayUs,
                    fifoReservedEventCount = spec.fifoReservedEventCount,
                    fifoMaxEventCount = spec.fifoMaxEventCount,
                    events = events,
                    timebaseAnomalies = timebaseAnomalies
                )
            }

        fun fromJson(json: JSONObject?): StepSensorMeta {
            if (json == null) return StepSensorMeta()
            return StepSensorMeta(
                present = json.opt("present") as? Boolean ?: false,
                used = json.opt("used") as? Boolean ?: false,
                name = json.opt("name") as? String,
                vendor = json.opt("vendor") as? String,
                version = json.int("version"),
                wakeUp = json.opt("wakeUp") as? Boolean,
                reportingMode = json.int("reportingMode"),
                minDelayUs = json.int("minDelayUs"),
                maxDelayUs = json.int("maxDelayUs"),
                fifoReservedEventCount = json.int("fifoReservedEventCount"),
                fifoMaxEventCount = json.int("fifoMaxEventCount"),
                events = (json.opt("events") as? Number)?.toLong() ?: 0,
                timebaseAnomalies = (json.opt("timebaseAnomalies") as? Number)?.toLong() ?: 0
            )
        }

        private fun JSONObject.int(key: String): Int? = (opt(key) as? Number)?.toInt()
    }
}

/**
 * meta.json の steps。歩数（試験機能）の記録の状態。
 * 項目が無い古い meta.json では SessionMeta.steps が null（記録なし）
 */
data class StepsMeta(
    /** 記録開始時に設定がオンだったか */
    val enabled: Boolean = false,
    /** 記録開始時に身体活動の権限があったか（Android 9 以前は権限不要なので true） */
    val permissionGranted: Boolean = false,
    /** [StepsStatus.jsonName]。知らない値もそのまま保つ */
    val status: String = StepsStatus.DISABLED.jsonName,
    val requestedMaxReportLatencyUs: Int = Steps.MAX_REPORT_LATENCY_US,
    val detector: StepSensorMeta = StepSensorMeta(),
    val counter: StepSensorMeta = StepSensorMeta()
) {
    /** steps.csv を作った記録か */
    val recorded: Boolean get() = status == StepsStatus.RECORDED.jsonName

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("permissionGranted", permissionGranted)
        put("status", status)
        put("requestedMaxReportLatencyUs", requestedMaxReportLatencyUs)
        put("detector", detector.toJson())
        put("counter", counter.toJson())
    }

    companion object {
        /** オブジェクトでなければ null（記録なし）。項目ごとに型が違えば既定値 */
        fun fromJson(value: Any?): StepsMeta? {
            val json = value as? JSONObject ?: return null
            val d = StepsMeta()
            return StepsMeta(
                enabled = json.opt("enabled") as? Boolean ?: d.enabled,
                permissionGranted = json.opt("permissionGranted") as? Boolean ?: d.permissionGranted,
                status = json.opt("status") as? String ?: d.status,
                requestedMaxReportLatencyUs =
                    (json.opt("requestedMaxReportLatencyUs") as? Number)?.toInt()
                        ?: d.requestedMaxReportLatencyUs,
                detector = StepSensorMeta.fromJson(json.optJSONObject("detector")),
                counter = StepSensorMeta.fromJson(json.optJSONObject("counter"))
            )
        }
    }
}
