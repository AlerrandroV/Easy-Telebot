package io.github.alerrandrov.easytelebot.data

import io.github.alerrandrov.easytelebot.engine.BotRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LinkButton(val text: String, val url: String)

/** Tipos aceitos pelo motor: photo, video, animation, audio, voice, document. */
data class MessageMedia(val type: String, val file: String, val name: String)

data class BotMessage(
    val text: String = "",
    val media: MessageMedia? = null,
    /** Linhas de botões (cada linha tem até 8 botões). */
    val buttons: List<List<LinkButton>> = emptyList(),
    val linkPreview: Boolean = false,
)

/** "Comando simples": um ou mais gatilhos (/start, /iniciar) → uma ou mais mensagens. */
data class SimpleCommand(
    val id: String = UUID.randomUUID().toString(),
    val triggers: List<String> = emptyList(),
    val description: String = "",
    val showInMenu: Boolean = true,
    val messages: List<BotMessage> = listOf(BotMessage()),
    /** `null` = qualquer pessoa. 1..10 = só admins dessa classe ou acima (1 = classe I). */
    val adminLevel: Int? = null,
    /** Se definido, o comando roda um script no Termux em vez de mandar as mensagens. */
    val script: CommandScript? = null,
)

/** Linha de comando rodada no Termux (ex.: `python ~/bot/clima.py`) e o tempo máximo em segundos. */
data class CommandScript(val command: String = "", val timeout: Int = 30)

/** Integração com o Termux: API local do bot (veja `termux.py`). */
data class TermuxSettings(
    val apiEnabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    /** Chave que os scripts usam no lugar do token (formato de token: "123:abc..."). */
    val key: String = "",
    /** "unhandled" = só o que o app não respondeu; "all" = tudo. */
    val forward: String = FORWARD_UNHANDLED,
) {
    companion object {
        const val DEFAULT_PORT = 8780
        const val FORWARD_UNHANDLED = "unhandled"
        const val FORWARD_ALL = "all"

        fun newKey(botId: Long): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
            val rnd = java.security.SecureRandom()
            return "$botId:" + (1..36).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        }
    }
}

/**
 * Configuração de um bot, salva em `config.json`. O motor Python só lê o arquivo
 * (veja `config.py`). O token NÃO fica aqui.
 */
/** Publicação em massa pelo Telegram (/post, /publi). */
data class BroadcastSettings(
    val enabled: Boolean = true,
    /** Classe mínima de admin para usar /post. */
    val adminLevel: Int = 1,
    val triggers: List<String> = listOf("post", "publi"),
)

/** Antispam: bloqueio automático de quem inunda o bot (veja `antispam.py`). */
data class AntispamSettings(
    val enabled: Boolean = true,
    /** "baixa", "media" ou "alta". */
    val sensitivity: String = "media",
    /** Classe mínima de admin que recebe o aviso e pode julgar. */
    val adminLevel: Int = 1,
    /** Avisar a pessoa de que foi bloqueada e que um admin vai analisar. */
    val warnUser: Boolean = true,
) {
    companion object {
        val LEVELS = listOf("baixa", "media", "alta")
    }
}

data class BotConfig(
    val autoMenu: Boolean = true,
    val commands: List<SimpleCommand> = emptyList(),
    val broadcast: BroadcastSettings = BroadcastSettings(),
    val antispam: AntispamSettings = AntispamSettings(),
    val shop: ShopSettings = ShopSettings(),
    val termux: TermuxSettings = TermuxSettings(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("auto_menu", autoMenu)
        put("commands", JSONArray().apply {
            commands.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id)
                    put("triggers", JSONArray(c.triggers))
                    put("description", c.description)
                    put("show_in_menu", c.showInMenu)
                    put("admin_level", c.adminLevel ?: JSONObject.NULL)
                    put("script", c.script?.let { JSONObject().put("command", it.command).put("timeout", it.timeout) } ?: JSONObject.NULL)
                    put("messages", JSONArray().apply {
                        c.messages.forEach { m -> put(messageJson(m)) }
                    })
                })
            }
        })
        put("broadcast", JSONObject()
            .put("enabled", broadcast.enabled)
            .put("admin_level", broadcast.adminLevel)
            .put("triggers", JSONArray(broadcast.triggers)))
        put("antispam", JSONObject()
            .put("enabled", antispam.enabled)
            .put("sensitivity", antispam.sensitivity)
            .put("admin_level", antispam.adminLevel)
            .put("warn_user", antispam.warnUser))
        put("shop", shop.toJson())
        put("termux", JSONObject()
            .put("api_enabled", termux.apiEnabled)
            .put("port", termux.port)
            .put("key", termux.key)
            .put("forward", termux.forward))
        put("keywords", JSONArray())
        put("welcome", JSONObject().put("enabled", false).put("text", ""))
        put("schedules", JSONArray())
        put("admins", JSONArray())
    }

    /** Arquivos de mídia usados (caminhos relativos à pasta do bot). */
    fun mediaFiles(): Set<String> =
        commands.flatMap { c -> c.messages.mapNotNull { it.media?.file } }.toSet() + shop.mediaFiles()

    companion object {
        const val VERSION = 2

        fun default() = BotConfig(
            commands = listOf(
                SimpleCommand(
                    triggers = listOf("start"),
                    description = "Começar",
                    messages = listOf(BotMessage("Olá, *{nome}*! Eu sou o {bot}. Use o menu para ver o que sei fazer.")),
                ),
            )
        )

        internal fun messageJson(m: BotMessage) = JSONObject().apply {
            put("text", m.text)
            put("media", m.media?.let {
                JSONObject().put("type", it.type).put("file", it.file).put("name", it.name)
            } ?: JSONObject.NULL)
            put("buttons", JSONArray().apply {
                m.buttons.forEach { row ->
                    put(JSONArray().apply {
                        row.forEach { b -> put(JSONObject().put("text", b.text).put("url", b.url)) }
                    })
                }
            })
            put("link_preview", m.linkPreview)
        }

        internal fun parseMessage(o: JSONObject): BotMessage {
            val media = o.optJSONObject("media")?.let {
                MessageMedia(it.optString("type"), it.optString("file"), it.optString("name"))
            }
            val rows = o.optJSONArray("buttons") ?: JSONArray()
            val buttons = (0 until rows.length()).mapNotNull { i ->
                val row = rows.optJSONArray(i) ?: return@mapNotNull null
                (0 until row.length()).mapNotNull { j ->
                    row.optJSONObject(j)?.let { LinkButton(it.optString("text"), it.optString("url")) }
                }.takeIf { it.isNotEmpty() }
            }
            return BotMessage(o.optString("text"), media, buttons, o.optBoolean("link_preview", false))
        }

        fun fromJson(json: JSONObject): BotConfig {
            val arr = json.optJSONArray("commands") ?: JSONArray()
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (o.has("triggers")) {
                    val t = o.getJSONArray("triggers")
                    val m = o.optJSONArray("messages") ?: JSONArray()
                    SimpleCommand(
                        id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                        triggers = (0 until t.length()).map { t.getString(it) },
                        description = o.optString("description"),
                        showInMenu = o.optBoolean("show_in_menu", true),
                        adminLevel = if (o.isNull("admin_level")) null else o.optInt("admin_level", 1).coerceIn(1, AdminLevels.MAX),
                        messages = (0 until m.length()).mapNotNull { j -> m.optJSONObject(j)?.let(::parseMessage) },
                        script = o.optJSONObject("script")?.let { s ->
                            CommandScript(s.optString("command"), s.optInt("timeout", 30).coerceIn(1, 300))
                        }?.takeIf { it.command.isNotBlank() },
                    )
                } else {
                    // versão 1: um comando → um texto
                    SimpleCommand(
                        triggers = listOf(o.optString("command")),
                        description = o.optString("description"),
                        messages = listOf(BotMessage(o.optString("response"))),
                    )
                }
            }
            val b = json.optJSONObject("broadcast")
            val broadcast = if (b == null) BroadcastSettings() else BroadcastSettings(
                enabled = b.optBoolean("enabled", true),
                adminLevel = b.optInt("admin_level", 1).coerceIn(1, AdminLevels.MAX),
                triggers = b.optJSONArray("triggers")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    ?.takeIf { it.isNotEmpty() } ?: listOf("post", "publi"),
            )
            val a = json.optJSONObject("antispam")
            val antispam = if (a == null) AntispamSettings() else AntispamSettings(
                enabled = a.optBoolean("enabled", true),
                sensitivity = a.optString("sensitivity", "media").takeIf { it in AntispamSettings.LEVELS } ?: "media",
                adminLevel = a.optInt("admin_level", 1).coerceIn(1, AdminLevels.MAX),
                warnUser = a.optBoolean("warn_user", true),
            )
            val tx = json.optJSONObject("termux")
            val termux = if (tx == null) TermuxSettings() else TermuxSettings(
                apiEnabled = tx.optBoolean("api_enabled", false),
                port = tx.optInt("port", TermuxSettings.DEFAULT_PORT).takeIf { it in 1024..65535 } ?: TermuxSettings.DEFAULT_PORT,
                key = tx.optString("key"),
                forward = if (tx.optString("forward") == TermuxSettings.FORWARD_ALL) TermuxSettings.FORWARD_ALL else TermuxSettings.FORWARD_UNHANDLED,
            )
            return BotConfig(json.optBoolean("auto_menu", true), list, broadcast, antispam,
                ShopSettings.fromJson(json.optJSONObject("shop")), termux)
        }
    }
}

object ConfigRepository {
    fun load(botId: Long): BotConfig {
        val f = BotRepository.configFile(botId)
        if (!f.exists()) return BotConfig.default().also { save(botId, it) }
        return try {
            BotConfig.fromJson(JSONObject(f.readText()))
        } catch (e: Exception) {
            BotConfig.default()
        }
    }

    /** Grava num arquivo temporário e renomeia (o motor nunca lê pela metade) e avisa o motor. */
    fun save(botId: Long, config: BotConfig) {
        val f = BotRepository.configFile(botId)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "config.json.tmp")
        tmp.writeText(config.toJson().toString(2))
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText())
            tmp.delete()
        }
        cleanupMedia(botId, config)
        BotRuntime.requestReload(botId)
    }

    /** Apaga arquivos de mídia que nenhuma mensagem usa mais. */
    private fun cleanupMedia(botId: Long, config: BotConfig) {
        val used = config.mediaFiles()
        BotRepository.mediaDir(botId).listFiles()?.forEach { file ->
            if ("media/${file.name}" !in used) file.delete()
        }
    }
}

/** Classes de admin: 1 = classe I (a mais alta) ... 10 = classe X. */
object AdminLevels {
    const val MAX = 10
    /** Comando secreto do motor para virar admin; não pode ser usado em comandos. */
    const val NEWADMIN = "newadmin"
    /** Comandos do próprio app que não podem virar comandos comuns. */
    val RESERVED = setOf(NEWADMIN, "post", "publi", ShopSettings.SUBSCRIPTIONS_TRIGGER) + ShopSettings.TRIGGERS
    private val ROMAN = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")

    fun roman(level: Int): String = ROMAN.getOrElse(level - 1) { level.toString() }

    /** Classe I pode tudo; classe N pode o que exige N ou mais (número maior). */
    fun canUse(adminLevel: Int, required: Int) = adminLevel <= required
}

/** Mesmas regras do motor Python (template.py). */
object Variables {
    val KNOWN = listOf("nome", "sobrenome", "usuario", "id", "grupo", "args", "data", "hora", "bot")
    private val PATTERN = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)\}""")
    val COMMAND_NAME = Regex("^[a-z0-9_]{1,32}$")

    fun unknown(text: String): List<String> =
        PATTERN.findAll(text).map { it.groupValues[1] }.filter { it !in KNOWN }.distinct().toList()

    /** Igual a `normalize_url` do motor. `null` se não for um link aceito. */
    fun normalizeUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return null
        if (Regex("^@[A-Za-z0-9_]{4,}$").matches(url)) return "https://t.me/" + url.drop(1)
        val low = url.lowercase()
        if (low.startsWith("https://") || low.startsWith("http://") || low.startsWith("tg://")) return url
        if (low.startsWith("t.me/") || low.startsWith("telegram.me/") || low.startsWith("www.")) return "https://$url"
        if (Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+(/.*)?$").matches(url)) return "https://$url"
        return null
    }
}
