package io.github.eightbrows.gpslogger.calc

/** 気圧の単位換算。航空・気象で使う水銀柱インチ（inHg）と hPa を相互に変換する */
object PressureUnits {

    /** 1 inHg = 33.8638866667 hPa（0 ℃ の水銀柱） */
    const val HPA_PER_INHG = 33.8638866667

    /** 29.92 inHg → 1013.2 hPa */
    fun hpaFromInHg(inHg: Double): Double = inHg * HPA_PER_INHG

    /** 1013.25 hPa → 29.92 inHg */
    fun inHgFromHpa(hpa: Double): Double = hpa / HPA_PER_INHG
}
