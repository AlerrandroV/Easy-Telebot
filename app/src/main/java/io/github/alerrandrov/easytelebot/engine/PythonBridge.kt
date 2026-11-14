package io.github.alerrandrov.easytelebot.engine

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

/** Funções expostas pelo motor Python (pacote `telebot_engine`). */
object PythonBridge {

    private fun python(context: Context): Python {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        return Python.getInstance()
    }

    private fun engineModule(context: Context): PyObject =
        python(context).getModule("telebot_engine.engine")

    data class BotInfo(val id: Long, val firstName: String, val username: String, val photoPath: String?)

    sealed interface CheckResult {
        data class Ok(val bot: BotInfo) : CheckResult
        data class Failed(val error: EngineError) : CheckResult
    }

    /** Chama `getMe`. Bloqueia: use fora da thread principal. */
    fun checkToken(context: Context, token: String): CheckResult {
        val photoDir = context.filesDir.absolutePath
        val json = JSONObject(engineModule(context).callAttr("check_token", token, photoDir).toString())
        if (!json.optBoolean("ok")) return CheckResult.Failed(EngineError.fromJson(json.getJSONObject("error")))
        val bot = json.getJSONObject("bot")
        return CheckResult.Ok(
            BotInfo(
                id = bot.optLong("id"),
                firstName = bot.optString("first_name"),
                username = bot.optString("username"),
                photoPath = json.optString("photo").takeIf { it.isNotEmpty() && it != "null" },
            )
        )
    }

    /** Remove o webhook do bot. Devolve `null` se deu certo. Bloqueia. */
    fun deleteWebhook(context: Context, token: String): EngineError? {
        val json = JSONObject(engineModule(context).callAttr("delete_webhook", token).toString())
        return if (json.optBoolean("ok")) null else EngineError.fromJson(json.getJSONObject("error"))
    }

    /** Cria o motor do bot. Chame `run` numa thread própria; `stop`/`wake` de qualquer thread. */
    fun createEngine(
        context: Context,
        token: String,
        configPath: String,
        callbacks: EngineCallbacks,
    ): PyObject = engineModule(context).callAttr(
        "Engine", token, configPath, context.filesDir.absolutePath, callbacks,
    )
}

/** Chamado pelo Python (Chaquopy expõe os métodos deste objeto ao motor). */
interface EngineCallbacks {
    fun onEvent(json: String)
    fun nowLocal(): String
}

/** Erro já classificado pelo motor (veja `errors.py`). */
data class EngineError(
    val code: String,
    val detail: String,
    val retryAfter: Int? = null,
    val retryIn: Int? = null,
    val context: String? = null,
    val fatal: Boolean = false,
) {
    companion object {
        fun fromJson(o: JSONObject) = EngineError(
            code = o.optString("code", "unknown"),
            detail = o.optString("detail"),
            retryAfter = o.optInt("retry_after", 0).takeIf { it > 0 },
            retryIn = o.optInt("retry_in", 0).takeIf { it > 0 },
            context = o.optString("context").takeIf { it.isNotEmpty() && it != "null" },
            fatal = o.optBoolean("fatal", false),
        )
    }
}
