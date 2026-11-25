package io.github.alerrandrov.easytelebot.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import io.github.alerrandrov.easytelebot.R
import java.util.Locale

/**
 * Idiomas do app. Para adicionar um: crie `res/values-<sigla>/strings.xml` com todas as
 * strings traduzidas, inclua a sigla em `res/xml/locales_config.xml` e em `ALL` abaixo,
 * e acrescente a tradução em `telebot_engine/i18n.py` (mensagens do bot).
 *
 * Android 13+: a escolha vai para o sistema (Configurações > Apps > Idioma também mostra).
 * Antes disso: fica guardada aqui e é aplicada ao abrir o app.
 */
object AppLanguages {
    data class Language(val code: String, val label: Int)

    val ALL = listOf(
        Language("pt-BR", R.string.language_pt),
        Language("en-US", R.string.language_en),
    )

    private const val PREFS = "app_language"
    private const val KEY = "tag"

    /** Idioma escolhido no app ("" = o do sistema). */
    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val list = context.getSystemService(LocaleManager::class.java).applicationLocales
            return if (list.isEmpty) "" else list[0].toLanguageTag()
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
    }

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return  // o sistema recria a tela sozinho
        }
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        activity.recreate()
    }

    /** Android 12 ou antes: aplica o idioma guardado (chamado em `attachBaseContext`). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
