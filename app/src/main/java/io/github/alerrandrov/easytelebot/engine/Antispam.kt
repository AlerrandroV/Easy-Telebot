package io.github.alerrandrov.easytelebot.engine

import android.content.Context
import io.github.alerrandrov.easytelebot.service.Alerts
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

    private fun module(context: Context) = PythonBridge.module(context, "antispam")

    private fun parse(o: JSONObject): SpamBan {
        val samples = o.optJSONArray("samples")
        return SpamBan(
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

    fun list(context: Context, dir: String): List<SpamBan> = try {
        val arr = JSONArray(module(context).callAttr("list_bans", dir).toString())
        (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    /** O bloqueio de uma pessoa, ou `null`. */
    fun get(context: Context, dir: String, userId: Long): SpamBan? = try {
        val raw = module(context).callAttr("get_ban", dir, userId).toString()
        if (raw == "null") null else parse(JSONObject(raw))
    } catch (e: Exception) {
        null
    }

    fun countPending(context: Context, dir: String): Int = try {
        module(context).callAttr("count_pending", dir).toInt()
    } catch (e: Exception) {
        0
    }

    /**
     * Desbloquear ou manter. Também atualiza os avisos dos admins no Telegram,
     * tira a notificação e avisa as telas.
     */
    fun decide(context: Context, token: String?, dir: String, botId: Long, userId: Long, action: String): Boolean {
        val ok = try {
            JSONObject(module(context).callAttr("decide_app", token ?: "", dir, userId, action).toString()).optBoolean("ok")
        } catch (e: Exception) {
            false
        }
        Alerts.dismissSpam(context, botId, userId)
        BotRuntime.bumpBans(botId)
        return ok
    }

    /** Bloqueio manual (pelo mensageiro). */
    fun block(context: Context, dir: String, botId: Long, userId: Long, firstName: String, lastName: String, username: String): Boolean {
        val ok = try {
            val user = JSONObject().put("id", userId).put("first_name", firstName).put("last_name", lastName).put("username", username)
            JSONObject(module(context).callAttr("block_app", dir, user.toString()).toString()).optBoolean("ok")
        } catch (e: Exception) {
            false
        }
        BotRuntime.bumpBans(botId)
        return ok
    }
}
