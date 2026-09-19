package io.github.eightbrows.gpslogger.settings

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** アプリの表示言語の選択 */
enum class LanguageMode(private val languageTag: String?) {
    SYSTEM(null),
    JAPANESE("ja"),
    ENGLISH("en");

    fun toLocales(): LocaleListCompat =
        if (languageTag == null) LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(languageTag)

    companion object {
        /** 適用中のロケールから逆引きする。対応していない言語はシステムに従う扱い */
        fun from(locales: LocaleListCompat): LanguageMode {
            if (locales.isEmpty) return SYSTEM
            return when (locales[0]?.language) {
                "ja" -> JAPANESE
                "en" -> ENGLISH
                else -> SYSTEM
            }
        }
    }
}

/**
 * アプリ別の表示言語。切り替えは AppCompatDelegate.setApplicationLocales() に任せる。
 *
 * - Android 13 以降: OS が保存し、Service を含むすべての Context に反映する。
 *   システム設定の「アプリの言語」からも変更できる。
 * - Android 12 以前: AppCompat が保存し（manifest の autoStoreLocales）、AppCompatActivity に反映する。
 *   Service には反映されないので、文字列は [localizedContext] から取る。
 *
 * どちらも Activity が作り直されるので、再起動しなくても即座に反映される。
 */
object AppLanguage {

    fun apply(mode: LanguageMode) {
        AppCompatDelegate.setApplicationLocales(mode.toLocales())
    }

    /** 実際に適用されている言語 */
    fun current(): LanguageMode = LanguageMode.from(AppCompatDelegate.getApplicationLocales())

    /** 言語タグごとに作った Context（applicationContext 由来なので Service を握らない） */
    @Volatile
    private var cache: Pair<String, Context>? = null

    /**
     * Activity 以外（Service など）で文字列を取るための Context。
     * Android 12 以前はアプリ別の言語が Service に反映されないため、ここで上書きする。
     */
    fun localizedContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return base

        val tags = locales.toLanguageTags()
        cache?.let { (cachedTags, context) -> if (cachedTags == tags) return context }

        val app = base.applicationContext
        val config = Configuration(app.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tags))
        return app.createConfigurationContext(config).also { cache = tags to it }
    }
}
