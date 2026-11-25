package io.github.eightbrows.gpslogger.calc

/** 歩数センサーの種類。steps.csv の type 列の値 */
enum class StepSensorType(val csvName: String) {
    /** TYPE_STEP_DETECTOR: 1 歩ごとに 1 件 */
    DETECTOR("detector"),

    /** TYPE_STEP_COUNTER: 端末の起動からの累計歩数 */
    COUNTER("counter")
}

/** steps.csv の 1 行 */
data class StepSample(
    val type: StepSensorType,
    /** SensorEvent.timestamp（elapsedRealtime 基準のはずの ns） */
    val eventElapsedNs: Long,
    /** 受け取ったときの SystemClock.elapsedRealtimeNanos() */
    val recvElapsedNs: Long,
    /** event_elapsed_ns を端末の壁時計（UTC ミリ秒）に直した値 */
    val epochMs: Long,
    /** counter の累計値。detector は null（CSV では空欄） */
    val count: Long?
)

/** 歩数センサーの諸元（meta.json に書く）。Android の Sensor から写した値 */
data class StepSensorSpec(
    val name: String,
    val vendor: String,
    val version: Int,
    val wakeUp: Boolean,
    val reportingMode: Int,
    val minDelayUs: Int,
    val maxDelayUs: Int,
    val fifoReservedEventCount: Int,
    val fifoMaxEventCount: Int
)

/** その記録で歩数をどう扱ったか。meta.json の steps.status の値 */
enum class StepsStatus(val jsonName: String) {
    /** 1 つ以上のセンサーを購読して steps.csv を作った */
    RECORDED("recorded"),

    /** 設定がオフ */
    DISABLED("disabled"),

    /** 身体活動の権限が無い */
    NO_PERMISSION("noPermission"),

    /** 使える（wake-up でない）歩数センサーが無い */
    NO_SENSOR("noSensor")
}

/** 記録開始時に決める、歩数の購読の計画。記録中は変えない */
data class StepsPlan(
    val status: StepsStatus,
    val useDetector: Boolean = false,
    val useCounter: Boolean = false
) {
    /** steps.csv を作り、センサーを購読するか */
    val record: Boolean get() = status == StepsStatus.RECORDED
}

/**
 * 時刻基準の異常の判定。counter は購読を始めるたびに、その時点の累計（基準値）を 1 件送ってくる。
 * その時刻は「最後に歩数が変わった時刻」で購読の開始より数分前のこともあるため、
 * 登録ごとの最初の counter の 1 件は異常として数えない。detector と 2 件目以降の counter は
 * [Steps.isTimebaseAnomaly] のとおり
 */
class StepTimebaseCheck {
    /** 今の登録で、まだ counter を 1 件も受け取っていないか */
    private var counterBaselinePending = true

    /** センサーを登録した（記録開始時・一時停止からの再開時）。次の counter の 1 件を基準値として扱う */
    fun onRegistered() {
        counterBaselinePending = true
    }

    /** 受け取った 1 件を時刻基準の異常として数えるか */
    fun isAnomaly(type: StepSensorType, eventElapsedNs: Long, recvElapsedNs: Long): Boolean {
        if (type == StepSensorType.COUNTER && counterBaselinePending) {
            counterBaselinePending = false
            return false
        }
        return Steps.isTimebaseAnomaly(eventElapsedNs, recvElapsedNs)
    }
}

/** 歩数（試験機能）の判定と書式。Android に依存しない（単体テストできる）部分をまとめる */
object Steps {

    const val FILE_NAME = "steps.csv"

    const val HEADER = "type,event_elapsed_ns,recv_elapsed_ns,epoch_ms,count"

    /** registerListener に渡す maxReportLatencyUs。ためずにすぐ届けてもらう */
    const val MAX_REPORT_LATENCY_US = 0

    /** 受け取りがイベント時刻からこれより遅れていたら、時刻基準の異常とみなす（60 秒） */
    const val MAX_DELIVERY_DELAY_NS = 60_000_000_000L

    /**
     * 記録開始時の計画。設定がオフ → 権限が無い → 使えるセンサーが無い、の順に判定する。
     * センサーは null（無い）か、wake-up 型しか無ければ使わない（1 歩ごとに CPU を起こさないため）
     */
    fun plan(
        enabled: Boolean,
        permissionGranted: Boolean,
        detector: StepSensorSpec?,
        counter: StepSensorSpec?
    ): StepsPlan {
        if (!enabled) return StepsPlan(StepsStatus.DISABLED)
        if (!permissionGranted) return StepsPlan(StepsStatus.NO_PERMISSION)
        val useDetector = usable(detector)
        val useCounter = usable(counter)
        if (!useDetector && !useCounter) return StepsPlan(StepsStatus.NO_SENSOR)
        return StepsPlan(StepsStatus.RECORDED, useDetector, useCounter)
    }

    /** 購読に使えるセンサーか（あって、wake-up でない） */
    fun usable(spec: StepSensorSpec?): Boolean = spec != null && !spec.wakeUp

    /**
     * イベント時刻を壁時計に直す: 受け取った時点の壁時計 − 受け取りまでの遅れ（ms）。
     * 遅れはそのまま差し引く（負や 60 秒超でも換算は同じ式）
     */
    fun epochMs(eventElapsedNs: Long, recvElapsedNs: Long, recvEpochMs: Long): Long =
        recvEpochMs - (recvElapsedNs - eventElapsedNs) / 1_000_000

    /**
     * SensorEvent.timestamp が elapsedRealtime 基準でないと疑われるか。
     * 受け取りがイベント時刻より前（負）、または 60 秒を超えて遅れていれば true
     */
    fun isTimebaseAnomaly(eventElapsedNs: Long, recvElapsedNs: Long): Boolean {
        val delay = recvElapsedNs - eventElapsedNs
        return delay < 0 || delay > MAX_DELIVERY_DELAY_NS
    }

    /** センサーの値から 1 行分を作る。counter の値（float）は整数の累計歩数にする */
    fun sample(
        type: StepSensorType,
        eventElapsedNs: Long,
        recvElapsedNs: Long,
        recvEpochMs: Long,
        value: Float
    ): StepSample = StepSample(
        type = type,
        eventElapsedNs = eventElapsedNs,
        recvElapsedNs = recvElapsedNs,
        epochMs = epochMs(eventElapsedNs, recvElapsedNs, recvEpochMs),
        count = if (type == StepSensorType.COUNTER) value.toLong() else null
    )

    /** steps.csv の 1 行（改行を含まない） */
    fun csvLine(s: StepSample): String =
        "${s.type.csvName},${s.eventElapsedNs},${s.recvElapsedNs},${s.epochMs},${s.count?.toString() ?: ""}"
}
