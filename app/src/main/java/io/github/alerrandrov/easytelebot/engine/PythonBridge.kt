package io.github.alerrandrov.easytelebot.engine

import android.content.Context
import com.chaquo.python.Kwarg
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

/** Funções expostas pelo motor Python (pacote `telebot_engine`). Todas bloqueiam: use fora da thread principal. */
object PythonBridge {

    @Volatile private var languageTag: String? = null

    private fun python(context: Context): Python {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        val py = Python.getInstance()
        // Idioma do app para os detalhes de erro gerados no Python (pode mudar com o app aberto).
        val tag = context.resources.configuration.locales[0].toLanguageTag()
        if (tag != languageTag) {
            py.getModule("telebot_engine.i18n").callAttr("set_app_language", tag)
            languageTag = tag
        }
        return py
    }

    internal fun module(context: Context, name: String): PyObject =
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

    // --- administradores (admins.py) ---

    data class Admin(
        val id: Long,
        val firstName: String,
        val lastName: String,
        val username: String,
        val level: Int,
        val addedAt: Long,
    ) {
        val displayName get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
    }

    data class AdminCode(val code: String, val level: Int, val expiresAt: Long)

    fun createAdminCode(context: Context, botDir: String, level: Int): AdminCode {
        val json = parse(module(context, "admins").callAttr("create_code", botDir, level))
        return AdminCode(json.getString("code"), json.getInt("level"), json.getLong("expires_at") * 1000)
    }

    fun cancelAdminCode(context: Context, botDir: String, code: String) {
        module(context, "admins").callAttr("cancel_code", botDir, code)
    }

    fun listAdmins(context: Context, botDir: String): List<Admin> {
        val arr = org.json.JSONArray(module(context, "admins").callAttr("list_admins", botDir).toString())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Admin(
                id = o.getLong("id"),
                firstName = o.optString("first_name"),
                lastName = o.optString("last_name"),
                username = o.optString("username"),
                level = o.optInt("level", 1),
                addedAt = o.optLong("added_at") * 1000,
            )
        }
    }

    fun setAdminLevel(context: Context, botDir: String, userId: Long, level: Int) {
        module(context, "admins").callAttr("set_level", botDir, userId, level)
    }

    fun removeAdmin(context: Context, botDir: String, userId: Long) {
        module(context, "admins").callAttr("remove_admin", botDir, userId)
    }

    // --- banco de dados (storage/__init__.py) ---

    private fun storageCall(context: Context, fn: String, vararg args: Any): Result<JSONObject> = guard {
        val json = parse(module(context, "storage").callAttr(fn, *args))
        if (json.optBoolean("ok")) Result.Ok(json) else Result.Failed(json.error())
    }

    /** Grava, lê e apaga um documento de teste (na AWS cria a tabela se faltar). */
    fun storageTest(context: Context, settingsJson: String, botDir: String, botId: Long) =
        storageCall(context, "test", settingsJson, botDir, botId.toString())

    fun storageBackup(context: Context, settingsJson: String, botDir: String, botId: Long) =
        storageCall(context, "backup", settingsJson, botDir, botId.toString())

    fun storageRestore(context: Context, settingsJson: String, botDir: String, botId: Long) =
        storageCall(context, "restore", settingsJson, botDir, botId.toString())

    fun storageList(context: Context, settingsJson: String, botDir: String, botId: Long, collection: String, limit: Int = 100) =
        storageCall(context, "list_docs", settingsJson, botDir, botId.toString(), collection, limit)

    // --- Python facilitado (pyfacil.py) ---

    data class CodeIssue(val line: Int, val message: String)
    data class CodeCheck(val ok: Boolean, val lang: String, val errors: List<CodeIssue>)

    /** Confere o código sem rodar (erros explicados, no idioma em que o código foi escrito). */
    fun checkCode(context: Context, code: String): CodeCheck = try {
        val appLang = context.resources.configuration.locales[0].language
        val json = parse(module(context, "code_runner").callAttr("check", code, null, appLang))
        val arr = json.optJSONArray("errors") ?: org.json.JSONArray()
        CodeCheck(
            json.optBoolean("ok"), json.optString("lang"),
            (0 until arr.length()).map { i -> arr.getJSONObject(i).let { CodeIssue(it.optInt("line"), it.optString("message")) } },
        )
    } catch (e: Exception) {
        CodeCheck(false, "", listOf(CodeIssue(0, e.message ?: e.toString())))
    }

    // --- internet (webfetch.py / feeds.py) ---

    data class HttpTest(val ok: Boolean, val status: Int, val value: String, val preview: String, val error: String)

    /** Roda um bloco "Requisição HTTP" com valores de exemplo (botão Testar). */
    fun testHttp(context: Context, blockJson: String): HttpTest = try {
        val lang = context.resources.configuration.locales[0].language
        val o = parse(module(context, "webfetch").callAttr("test_block", blockJson, lang))
        HttpTest(o.optBoolean("ok"), o.optInt("status"), o.optString("value"), o.optString("preview"), o.optString("error"))
    } catch (e: Exception) {
        HttpTest(false, 0, "", "", e.message ?: e.toString())
    }

    data class FeedPreview(val ok: Boolean, val url: String, val error: String, val titles: List<String>)

    fun previewFeed(context: Context, url: String): FeedPreview = try {
        val o = parse(module(context, "feeds").callAttr("preview", url))
        val arr = o.optJSONArray("items") ?: org.json.JSONArray()
        FeedPreview(o.optBoolean("ok"), o.optString("url"), o.optString("error"),
            (0 until arr.length()).map { arr.getJSONObject(it).optString("title") })
    } catch (e: Exception) {
        FeedPreview(false, url, e.message ?: e.toString(), emptyList())
    }

    // --- agenda e formulários (booking.py / forms.py) ---

    data class BookingItem(val id: String, val name: String, val username: String, val at: String)

    fun listBookings(context: Context, botDir: String): List<BookingItem> = try {
        val arr = org.json.JSONArray(module(context, "booking").callAttr("list_bookings", botDir).toString())
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { BookingItem(it.optString("id"), it.optString("name"), it.optString("username"), it.optString("at")) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Cancela pelo app e avisa a pessoa no Telegram. */
    fun cancelBooking(context: Context, token: String, configPath: String, id: String): Boolean = try {
        parse(module(context, "booking").callAttr("app_cancel", token, configPath, id)).optBoolean("ok")
    } catch (e: Exception) {
        false
    }

    data class FormResponse(val at: Long, val name: String, val username: String, val answers: List<String>)

    fun formResponses(context: Context, botDir: String, formId: String): List<FormResponse> = try {
        val arr = org.json.JSONArray(module(context, "forms").callAttr("list_responses", botDir, formId).toString())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val a = o.optJSONArray("answers") ?: org.json.JSONArray()
            FormResponse(o.optLong("at") * 1000, o.optString("name"), o.optString("username"), (0 until a.length()).map { a.optString(it) })
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun formCsv(context: Context, botDir: String, formId: String, questions: List<String>): String =
        module(context, "forms").callAttr("export_csv", botDir, formId, org.json.JSONArray(questions).toString(),
            context.resources.configuration.locales[0].language).toString()

    fun clearForm(context: Context, botDir: String, formId: String) {
        module(context, "forms").callAttr("clear", botDir, formId)
    }

    /** Cria o motor de um bot. Chame `run` numa thread própria; `stop`/`wake`/`request_reload` de qualquer thread. */
    fun createEngine(
        context: Context,
        token: String,
        configPath: String,
        dataDir: String,
        callbacks: EngineCallbacks,
        storageJson: String? = null,
        paymentsJson: String? = null,
    ): PyObject = module(context, "engine").callAttr(
        "Engine", token, configPath, dataDir, callbacks, Kwarg("storage", storageJson), Kwarg("payments", paymentsJson),
    )
}

/** Chamado pelo Python (Chaquopy expõe os métodos deste objeto ao motor). */
interface EngineCallbacks {
    fun onEvent(json: String)
    fun nowLocal(): String
    /** Comando de script: roda no Termux e devolve o resultado em JSON (veja `TermuxRunner`). */
    fun runTermux(command: String, envJson: String, timeoutSec: Int): String
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
