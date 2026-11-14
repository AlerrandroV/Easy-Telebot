package io.github.alerrandrov.easytelebot.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** Dados de um bot que não são segredo (o token fica no [TokenStore]). */
data class BotMeta(
    val id: Long,
    val name: String,
    val username: String,
    /** Ligar junto com o celular. */
    val autoStart: Boolean = false,
    /** O usuário quer este bot ligado (para religar depois de o sistema matar o app). */
    val shouldRun: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** Muda quando a foto é trocada, para a tela recarregar a imagem. */
    val photoVersion: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("username", username)
        .put("auto_start", autoStart).put("should_run", shouldRun)
        .put("created_at", createdAt).put("photo_version", photoVersion)

    companion object {
        fun fromJson(o: JSONObject) = BotMeta(
            id = o.getLong("id"),
            name = o.optString("name"),
            username = o.optString("username"),
            autoStart = o.optBoolean("auto_start"),
            shouldRun = o.optBoolean("should_run"),
            createdAt = o.optLong("created_at"),
            photoVersion = o.optLong("photo_version"),
        )
    }
}

/**
 * Cada bot tem uma pasta `files/bots/<id>/` com:
 * `meta.json`, `config.json` (lido pelo motor), `photo.jpg`, `media/` e `media_cache.json`.
 * O id é o número do bot no Telegram (o começo do token, antes do ":").
 */
object BotRepository {
    private val _bots = MutableStateFlow<List<BotMeta>>(emptyList())
    val bots: StateFlow<List<BotMeta>> = _bots.asStateFlow()

    private lateinit var root: File

    fun init(context: Context) {
        root = File(context.filesDir, "bots").apply { mkdirs() }
        migrateLegacy(context)
        reload()
    }

    fun idFromToken(token: String): Long? = token.trim().substringBefore(":").toLongOrNull()

    fun dir(id: Long) = File(root, id.toString())
    fun configFile(id: Long) = File(dir(id), "config.json")
    fun photoFile(id: Long) = File(dir(id), "photo.jpg")
    fun mediaDir(id: Long) = File(dir(id), "media")

    fun get(id: Long): BotMeta? = _bots.value.firstOrNull { it.id == id }

    @Synchronized
    fun save(meta: BotMeta) {
        dir(meta.id).mkdirs()
        val f = File(dir(meta.id), "meta.json")
        val tmp = File(dir(meta.id), "meta.json.tmp")
        tmp.writeText(meta.toJson().toString())
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        reload()
    }

    fun update(id: Long, change: (BotMeta) -> BotMeta) {
        get(id)?.let { save(change(it)) }
    }

    @Synchronized
    fun delete(context: Context, id: Long) {
        dir(id).deleteRecursively()
        TokenStore.delete(context, id)
        Prefs(context).forgetBot(id)
        reload()
    }

    @Synchronized
    fun reload() {
        _bots.value = (root.listFiles() ?: emptyArray())
            .mapNotNull { d ->
                try {
                    BotMeta.fromJson(JSONObject(File(d, "meta.json").readText()))
                } catch (e: Exception) {
                    null
                }
            }
            .sortedBy { it.createdAt }
    }

    /** Versão 0.1 guardava um bot só, direto em `files/`. Move tudo para `files/bots/<id>/`. */
    private fun migrateLegacy(context: Context) {
        val token = TokenStore.loadLegacy(context) ?: return
        val id = idFromToken(token) ?: return
        val prefs = Prefs(context)
        val d = dir(id).apply { mkdirs() }
        File(context.filesDir, "config.json").takeIf { it.exists() }?.renameTo(File(d, "config.json"))
        File(context.filesDir, "bot_photo.jpg").takeIf { it.exists() }?.renameTo(File(d, "photo.jpg"))
        TokenStore.save(context, id, token)
        val meta = BotMeta(
            id = id,
            name = prefs.legacyBotName,
            username = prefs.legacyBotUsername,
            autoStart = prefs.legacyStartOnBoot,
            shouldRun = prefs.legacyShouldRun,
        )
        File(d, "meta.json").writeText(meta.toJson().toString())
        TokenStore.clearLegacy(context)
        prefs.clearLegacy()
    }
}
