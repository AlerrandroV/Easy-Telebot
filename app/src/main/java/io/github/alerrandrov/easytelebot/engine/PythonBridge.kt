package io.github.alerrandrov.easytelebot.engine

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

/** Funções expostas pelo motor Python (pacote `telebot_engine`). Todas bloqueiam: use fora da thread principal. */
object PythonBridge {

    private fun python(context: Context): Python {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        return Python.getInstance()
    }

    private fun module(context: Context, name: String): PyObject =
        python(context).getModule("telebot_engine.$name")

    data class BotInfo(val id: Long, val firstName: String, val username: String, val photoPath: String?)

    data class Profile(
        val name: String,
        val description: String,
        val shortDescription: String,
        val username: String,
        val photoPath: String?,
    )

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Failed(val error: EngineError) : Result<Nothing>
    }

    private fun parse(raw: PyObject): JSONObject = JSONObject(raw.toString())

    private fun JSONObject.error() = EngineError.fromJson(getJSONObject("error"))

    private fun JSONObject.photo(): String? =
        optString("photo").takeIf { it.isNotEmpty() && it != "null" }

    /** Roda [block] e transforma qualquer exceção em erro explicável. */
    private inline fun <T> guard(block: () -> Result<T>): Result<T> = try {
        block()
    } catch (e: Exception) {
        Result.Failed(EngineError("unknown", e.message ?: e.toString()))
    }

    /** Valida o token (`getMe`) e baixa a foto para [photoPath]. */
    fun checkToken(context: Context, token: String, photoPath: String): Result<BotInfo> = guard {
        val json = parse(module(context, "engine").callAttr("check_token", token, photoPath))
        if (!json.optBoolean("ok")) return@guard Result.Failed(json.error())
        val bot = json.getJSONObject("bot")
        Result.Ok(BotInfo(bot.optLong("id"), bot.optString("first_name"), bot.optString("username"), json.photo()))
    }

    /** Remove o webhook do bot. */
    fun deleteWebhook(context: Context, token: String): Result<Unit> = guard {
        val json = parse(module(context, "engine").callAttr("delete_webhook", token))
        if (json.optBoolean("ok")) Result.Ok(Unit) else Result.Failed(json.error())
    }

    fun getProfile(context: Context, token: String, photoPath: String): Result<Profile> = guard {
        val json = parse(module(context, "profile").callAttr("get_profile", token, photoPath))
        if (!json.optBoolean("ok")) return@guard Result.Failed(json.error())
        Result.Ok(
            Profile(
                name = json.optString("name"),
                description = json.optString("description"),
                shortDescription = json.optString("short_description"),
                username = json.getJSONObject("bot").optString("username"),
                photoPath = json.photo(),
            )
        )
    }

    private fun simple(context: Context, fn: String, vararg args: Any): Result<Unit> = guard {
        val json = parse(module(context, "profile").callAttr(fn, *args))
        if (json.optBoolean("ok")) Result.Ok(Unit) else Result.Failed(json.error())
    }

    fun setName(context: Context, token: String, name: String) = simple(context, "set_name", token, name)
    fun setDescription(context: Context, token: String, text: String) = simple(context, "set_description", token, text)
    fun setShortDescription(context: Context, token: String, text: String) =
        simple(context, "set_short_description", token, text)

    /** Troca a foto de perfil e baixa a nova para [photoPath]. */
    fun setPhoto(context: Context, token: String, imagePath: String, photoPath: String): Result<String?> = guard {
        val json = parse(module(context, "profile").callAttr("set_photo", token, imagePath, photoPath))
        if (json.optBoolean("ok")) Result.Ok(json.photo()) else Result.Failed(json.error())
    }

    /** Cria o motor de um bot. Chame `run` numa thread própria; `stop`/`wake`/`request_reload` de qualquer thread. */
    fun createEngine(
        context: Context,
        token: String,
        configPath: String,
        dataDir: String,
        callbacks: EngineCallbacks,
    ): PyObject = module(context, "engine").callAttr("Engine", token, configPath, dataDir, callbacks)
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
