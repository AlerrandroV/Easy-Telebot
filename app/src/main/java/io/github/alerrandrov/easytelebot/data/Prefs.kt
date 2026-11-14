package io.github.alerrandrov.easytelebot.data

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/** Preferências simples do app (nada sensível aqui: o token fica no [TokenStore]). */
class Prefs(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** O usuário quer o bot ligado. Se o serviço não estiver rodando, o sistema o matou. */
    var shouldRun: Boolean
        get() = p.getBoolean("should_run", false)
        set(v) = p.edit().putBoolean("should_run", v).apply()

    var startOnBoot: Boolean
        get() = p.getBoolean("start_on_boot", false)
        set(v) = p.edit().putBoolean("start_on_boot", v).apply()

    /** Momento em que detectamos que o sistema matou o bot (0 = nada a avisar). */
    var killedAt: Long
        get() = p.getLong("killed_at", 0)
        set(v) = p.edit().putLong("killed_at", v).apply()

    var batteryGuideSeen: Boolean
        get() = p.getBoolean("battery_guide_seen", false)
        set(v) = p.edit().putBoolean("battery_guide_seen", v).apply()

    var botName: String
        get() = p.getString("bot_name", "") ?: ""
        set(v) = p.edit().putString("bot_name", v).apply()

    var botUsername: String
        get() = p.getString("bot_username", "") ?: ""
        set(v) = p.edit().putString("bot_username", v).apply()

    var botPhoto: String?
        get() = p.getString("bot_photo", null)
        set(v) = p.edit().putString("bot_photo", v).apply()

    /** Mensagens recebidas hoje (zera quando o dia muda). */
    fun messagesToday(): Int =
        if (p.getString("count_day", "") == LocalDate.now().toString()) p.getInt("count", 0) else 0

    fun incrementMessagesToday(): Int {
        val n = messagesToday() + 1
        p.edit().putString("count_day", LocalDate.now().toString()).putInt("count", n).apply()
        return n
    }
}
