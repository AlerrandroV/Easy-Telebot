package io.github.alerrandrov.easytelebot.data

import io.github.alerrandrov.easytelebot.engine.BotRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Botão embaixo da mensagem. [url] guarda o valor de acordo com o [type]:
 * link (url), texto a copiar (copy), id da etapa (node), endereço do mini app (webapp)
 * ou o dado entregue aos scripts (callback).
 */
data class LinkButton(val text: String, val url: String, val type: String = ButtonTypes.URL) {
    fun toJson(): JSONObject = JSONObject().put("text", text).put("type", type).put("value", url).apply {
        if (type in ButtonTypes.LINKS) put("url", url)
    }

    companion object {
        fun fromJson(o: JSONObject): LinkButton {
            val type = o.optString("type", ButtonTypes.URL).takeIf { it in ButtonTypes.ALL } ?: ButtonTypes.URL
            return LinkButton(o.optString("text"), o.optString("value").ifEmpty { o.optString("url") }, type)
        }
    }
}

/** Tipos de botão (mesmos nomes do motor, `engine.build_button`). */
object ButtonTypes {
    const val URL = "url"
    const val COPY = "copy"
    const val NODE = "node"
    const val WEBAPP = "webapp"
    const val CALLBACK = "callback"
    /** Links para usuário, canal ou grupo (@nome ou t.me/...): no Telegram são links comuns. */
    const val USER = "user"
    const val CHANNEL = "channel"
    const val GROUP = "group"
    val LINKS = setOf(URL, USER, CHANNEL, GROUP)
    val ALL = listOf(URL, USER, CHANNEL, GROUP, COPY, WEBAPP, NODE, CALLBACK)

    /** `nodeIds` = etapas existentes (`null` = não conferir). */
    fun isValid(b: LinkButton, nodeIds: Set<String>? = null): Boolean = b.text.isNotBlank() && when (b.type) {
        COPY -> b.url.isNotBlank() && b.url.length <= 256
        NODE -> b.url.isNotBlank() && (nodeIds == null || b.url in nodeIds)
        WEBAPP -> Variables.normalizeUrl(b.url)?.startsWith("https://") == true
        CALLBACK -> b.url.isNotBlank() && b.url.toByteArray().size <= 61
        else -> Variables.normalizeUrl(b.url) != null
    }
}

/** Etapa de um fluxo (veja `flows.py`). */
data class FlowNode(
    val id: String = UUID.randomUUID().toString().take(8),
    val title: String = "",
    val messages: List<BotMessage> = listOf(BotMessage()),
    /** Aberta por botão: edita a mensagem do menu em vez de mandar outra. */
    val edit: Boolean = true,
    /** Pausa (segundos, com "digitando…") antes de mandar. */
    val delay: Int = 0,
    val after: FlowAfter = FlowAfter(),
) {
    /** Etapas para onde esta leva (botões e "depois"). */
    fun links(): List<String> =
        (messages.flatMap { m -> m.buttons.flatten().filter { it.type == ButtonTypes.NODE }.map { it.url } } +
            listOfNotNull(after.node.takeIf { after.type != FlowAfter.NONE })).filter { it.isNotBlank() }.distinct()
}

/** O que acontece depois de mandar as mensagens da etapa. */
data class FlowAfter(
    /** "none", "goto" (segue sozinha) ou "ask" (espera resposta). */
    val type: String = NONE,
    val node: String? = null,
    val variable: String = "",
    /** "text", "number", "email" ou "phone". */
    val validate: String = "text",
    val invalid: String = "",
) {
    companion object {
        const val NONE = "none"
        const val GOTO = "goto"
        const val ASK = "ask"
        val VALIDATIONS = listOf("text", "number", "email", "phone")
        val VAR_NAME = Regex("^[a-z_][a-z0-9_]{0,31}$")
    }
}

/** Tipos aceitos pelo motor: photo, video, animation, audio, voice, document. */
data class MessageMedia(val type: String, val file: String, val name: String)

data class BotMessage(
    val text: String = "",
    val media: MessageMedia? = null,
    /** Linhas de botões (cada linha tem até 8 botões). */
    val buttons: List<List<LinkButton>> = emptyList(),
    val linkPreview: Boolean = false,
)

/**
 * Comando: um ou mais gatilhos (/start, /iniciar) e a resposta em **blocos**, executados
 * em ordem (mensagem, script do Termux, início de fluxo). Mesmo formato de `config.py`.
 */
data class SimpleCommand(
    val id: String = UUID.randomUUID().toString(),
    val triggers: List<String> = emptyList(),
    val description: String = "",
    val showInMenu: Boolean = true,
    /** `null` = qualquer pessoa. 1..10 = só admins dessa classe ou acima (1 = classe I). */
    val adminLevel: Int? = null,
    val blocks: List<CommandBlock> = emptyList(),
) {
    val messages: List<BotMessage> get() = blocks.filterIsInstance<CommandBlock.Message>().map { it.msg }
    val script: CommandScript? get() = blocks.filterIsInstance<CommandBlock.Termux>().firstOrNull()?.script
    val flow: String? get() = blocks.filterIsInstance<CommandBlock.Flow>().firstOrNull()?.node
}

/** Um bloco da resposta de um comando. [key] só identifica o bloco na tela (não é salvo). */
sealed interface CommandBlock {
    val key: String

    data class Message(val msg: BotMessage, override val key: String = newKey()) : CommandBlock
    data class Termux(val script: CommandScript, override val key: String = newKey()) : CommandBlock
    data class Flow(val node: String, override val key: String = newKey()) : CommandBlock

    companion object {
        fun newKey() = UUID.randomUUID().toString()

        fun toJson(b: CommandBlock): JSONObject = when (b) {
            is Message -> BotConfig.messageJson(b.msg).put("type", "message")
            is Termux -> JSONObject().put("type", "termux").put("command", b.script.command).put("timeout", b.script.timeout)
            is Flow -> JSONObject().put("type", "flow").put("node", b.node)
        }

        fun fromJson(o: JSONObject): CommandBlock? = when (o.optString("type")) {
            "message" -> Message(BotConfig.parseMessage(o))
            "termux" -> Termux(CommandScript(o.optString("command"), o.optInt("timeout", 30).coerceIn(1, 300)))
            "flow" -> o.optString("node").takeIf { it.isNotBlank() }?.let { Flow(it) }
            else -> null
        }
    }
}

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
    val flows: List<FlowNode> = emptyList(),
) {
    /** Variáveis criadas pelas perguntas dos fluxos (ex.: {cidade}). */
    fun flowVariables(): Set<String> =
        flows.filter { it.after.type == FlowAfter.ASK }.map { it.after.variable }.toSet() + if (flows.any { it.after.type == FlowAfter.ASK }) setOf("resposta") else emptySet()

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
                    put("blocks", JSONArray().apply { c.blocks.forEach { put(CommandBlock.toJson(it)) } })
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
        put("flows", JSONObject().put("nodes", JSONArray().apply {
            flows.forEach { n ->
                put(JSONObject()
                    .put("id", n.id)
                    .put("title", n.title)
                    .put("messages", JSONArray().apply { n.messages.forEach { put(messageJson(it)) } })
                    .put("edit", n.edit)
                    .put("delay", n.delay)
                    .put("after", JSONObject()
                        .put("type", n.after.type)
                        .put("node", n.after.node ?: JSONObject.NULL)
                        .put("variable", n.after.variable)
                        .put("validate", n.after.validate)
                        .put("invalid", n.after.invalid)))
            }
        }))
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
        commands.flatMap { c -> c.messages.mapNotNull { it.media?.file } }.toSet() + shop.mediaFiles() +
            flows.flatMap { n -> n.messages.mapNotNull { it.media?.file } }

    companion object {
        const val VERSION = 2

        fun default() = BotConfig(
            commands = listOf(
                SimpleCommand(
                    triggers = listOf("start"),
                    description = "Começar",
                    blocks = listOf(CommandBlock.Message(BotMessage("Olá, *{nome}*! Eu sou o {bot}. Use o menu para ver o que sei fazer."))),
                ),
            )
        )

        /** Comandos de antes dos blocos: fluxo, script ou lista de mensagens. */
        private fun legacyBlocks(o: JSONObject, messages: JSONArray): List<CommandBlock> {
            if (!o.isNull("flow") && o.optString("flow").isNotBlank()) return listOf(CommandBlock.Flow(o.optString("flow")))
            o.optJSONObject("script")?.let { s ->
                if (s.optString("command").isNotBlank()) {
                    return listOf(CommandBlock.Termux(CommandScript(s.optString("command"), s.optInt("timeout", 30).coerceIn(1, 300))))
                }
            }
            return (0 until messages.length()).mapNotNull { j -> messages.optJSONObject(j)?.let(::parseMessage) }
                .map { CommandBlock.Message(it) }
        }

        internal fun messageJson(m: BotMessage) = JSONObject().apply {
            put("text", m.text)
            put("media", m.media?.let {
                JSONObject().put("type", it.type).put("file", it.file).put("name", it.name)
            } ?: JSONObject.NULL)
            put("buttons", JSONArray().apply {
                m.buttons.forEach { row ->
                    put(JSONArray().apply {
                        row.forEach { b -> put(b.toJson()) }
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
                    row.optJSONObject(j)?.let { LinkButton.fromJson(it) }
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
                        blocks = o.optJSONArray("blocks")?.let { bl ->
                            (0 until bl.length()).mapNotNull { j -> bl.optJSONObject(j)?.let(CommandBlock::fromJson) }
                        } ?: legacyBlocks(o, m),
                    )
                } else {
                    // versão 1: um comando → um texto
                    SimpleCommand(
                        triggers = listOf(o.optString("command")),
                        description = o.optString("description"),
                        blocks = listOf(CommandBlock.Message(BotMessage(o.optString("response")))),
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
            val nodesArr = json.optJSONObject("flows")?.optJSONArray("nodes") ?: JSONArray()
            val nodes = (0 until nodesArr.length()).mapNotNull { i ->
                val n = nodesArr.optJSONObject(i) ?: return@mapNotNull null
                val m = n.optJSONArray("messages") ?: JSONArray()
                val a = n.optJSONObject("after") ?: JSONObject()
                FlowNode(
                    id = n.optString("id").ifEmpty { UUID.randomUUID().toString().take(8) },
                    title = n.optString("title"),
                    messages = (0 until m.length()).mapNotNull { j -> m.optJSONObject(j)?.let(::parseMessage) }.ifEmpty { listOf(BotMessage()) },
                    edit = n.optBoolean("edit", true),
                    delay = n.optInt("delay", 0).coerceIn(0, 30),
                    after = FlowAfter(
                        type = a.optString("type", FlowAfter.NONE).takeIf { it in listOf(FlowAfter.NONE, FlowAfter.GOTO, FlowAfter.ASK) } ?: FlowAfter.NONE,
                        node = if (a.isNull("node")) null else a.optString("node").ifEmpty { null },
                        variable = a.optString("variable"),
                        validate = a.optString("validate", "text").takeIf { it in FlowAfter.VALIDATIONS } ?: "text",
                        invalid = a.optString("invalid"),
                    ),
                )
            }
            return BotConfig(json.optBoolean("auto_menu", true), list, broadcast, antispam,
                ShopSettings.fromJson(json.optJSONObject("shop")), termux, nodes)
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
