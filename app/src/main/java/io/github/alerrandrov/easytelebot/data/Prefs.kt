package io.github.alerrandrov.easytelebot.data

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/** Preferências gerais do app (nada sensível aqui: os tokens ficam no [TokenStore]). */
class Prefs(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** Momento em que detectamos que o sistema matou algum bot (0 = nada a avisar). */
    var killedAt: Long
        get() = p.getLong("killed_at", 0)
        set(v) = p.edit().putLong("killed_at", v).apply()

    var batteryGuideSeen: Boolean
        get() = p.getBoolean("battery_guide_seen", false)
        set(v) = p.edit().putBoolean("battery_guide_seen", v).apply()

    /** Mensagens recebidas hoje por um bot (zera quando o dia muda). */
    fun messagesToday(botId: Long): Int =
        if (p.getString("count_day_$botId", "") == LocalDate.now().toString()) p.getInt("count_$botId", 0) else 0

    fun incrementMessagesToday(botId: Long): Int {
        val n = messagesToday(botId) + 1
        p.edit().putString("count_day_$botId", LocalDate.now().toString()).putInt("count_$botId", n).apply()
        return n
    }

    fun forgetBot(botId: Long) {
        p.edit().remove("count_day_$botId").remove("count_$botId").apply()
    }

    // --- campos da versão 0.1 (um bot só), lidos só na migração ---
    val legacyShouldRun get() = p.getBoolean("should_run", false)
    val legacyStartOnBoot get() = p.getBoolean("start_on_boot", false)
    val legacyBotName get() = p.getString("bot_name", "") ?: ""
    val legacyBotUsername get() = p.getString("bot_username", "") ?: ""

    fun clearLegacy() {
        p.edit().remove("should_run").remove("start_on_boot").remove("bot_name")
            .remove("bot_username").remove("bot_photo").remove("count_day").remove("count").apply()
    }
}
