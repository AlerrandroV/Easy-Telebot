package io.github.alerrandrov.easytelebot.engine

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject

/** Uma pessoa bloqueada pelo antispam (ou manualmente), esperando ou já julgada por um admin. */
data class SpamBan(
    val userId: Long,
    val firstName: String,
    val lastName: String,
    val username: String,
    val chatTitle: String,
    val isPrivate: Boolean,
    /** "flood", "repeat" ou "manual". */
    val reason: String,
    val reasonText: String,
    val at: Long,
    /** "pending" = esperando um admin; "kept" = um admin decidiu manter. */
    val status: String,
    val samples: List<String>,
    val decidedBy: String,
) {
    val pending get() = status == "pending"
    val displayName get() = "$firstName $lastName".trim().ifEmpty { if (username.isNotEmpty()) "@$username" else userId.toString() }
}

/** Ponte para `antispam.py`. Tudo bloqueia: chame fora da thread principal. */
object AntispamBridge {
    const val RELEASE = "release"
    const val KEEP = "keep"

    private fun module(context: Context) = run {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        Python.getInstance().getModule("telebot_engine.antispam")
    }

    fun list(context: Context, dir: String): List<SpamBan> = try {
        val arr = JSONArray(module(context).callAttr("list_bans", dir).toString())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val samples = o.optJSONArray("samples")
            SpamBan(
                userId = o.optLong("id"),
                firstName = o.optString("first_name"),
                lastName = o.optString("last_name"),
                username = o.optString("username"),
                chatTitle = o.optString("chat_title"),
                isPrivate = o.optString("chat_type", "private") == "private",
                reason = o.optString("reason"),
                reasonText = o.optString("reason_text"),
                at = o.optLong("at") * 1000,
                status = o.optString("status", "pending"),
                samples = if (samples == null) emptyList() else (0 until samples.length()).map { samples.getString(it) },
                decidedBy = o.optString("decided_by"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Desbloquear ou manter. Também atualiza os avisos dos admins no Telegram. */
    fun decide(context: Context, token: String?, dir: String, userId: Long, action: String): Boolean = try {
        JSONObject(module(context).callAttr("decide_app", token ?: "", dir, userId, action).toString()).optBoolean("ok")
    } catch (e: Exception) {
        false
    }

    /** Bloqueio manual (pelo mensageiro). */
    fun block(context: Context, dir: String, userId: Long, firstName: String, lastName: String, username: String): Boolean = try {
        val user = JSONObject().put("id", userId).put("first_name", firstName).put("last_name", lastName).put("username", username)
        JSONObject(module(context).callAttr("block_app", dir, user.toString()).toString()).optBoolean("ok")
    } catch (e: Exception) {
        false
    }
}
