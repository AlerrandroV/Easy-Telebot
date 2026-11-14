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
)

/**
 * Configuração de um bot, salva em `config.json`. O motor Python só lê o arquivo
 * (veja `config.py`). O token NÃO fica aqui.
 */
data class BotConfig(
    val autoMenu: Boolean = true,
    val commands: List<SimpleCommand> = emptyList(),
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
                    put("messages", JSONArray().apply {
                        c.messages.forEach { m -> put(messageJson(m)) }
                    })
                })
            }
        })
        put("keywords", JSONArray())
        put("welcome", JSONObject().put("enabled", false).put("text", ""))
        put("schedules", JSONArray())
        put("admins", JSONArray())
    }

    /** Arquivos de mídia usados (caminhos relativos à pasta do bot). */
    fun mediaFiles(): Set<String> =
        commands.flatMap { c -> c.messages.mapNotNull { it.media?.file } }.toSet()

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

        private fun messageJson(m: BotMessage) = JSONObject().apply {
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

        private fun parseMessage(o: JSONObject): BotMessage {
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
                        messages = (0 until m.length()).mapNotNull { j -> m.optJSONObject(j)?.let(::parseMessage) },
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
            return BotConfig(json.optBoolean("auto_menu", true), list)
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
