package io.github.alerrandrov.easytelebot.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BotCommand(
    val command: String,
    val description: String,
    val response: String,
    val adminOnly: Boolean = false,
)

/**
 * Configuração do bot (comandos, respostas...). Fica em `config.json`; o motor
 * Python só lê o arquivo e recarrega sozinho quando ele muda.
 * O token NÃO fica aqui.
 */
data class BotConfig(val commands: List<BotCommand>) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("commands", JSONArray().apply {
            commands.forEach { c ->
                put(JSONObject().apply {
                    put("command", c.command)
                    put("description", c.description)
                    put("response", c.response)
                    put("admin_only", c.adminOnly)
                    put("buttons", JSONArray())
                })
            }
        })
        put("keywords", JSONArray())
        put("welcome", JSONObject().put("enabled", false).put("text", ""))
        put("schedules", JSONArray())
        put("admins", JSONArray())
    }

    companion object {
        const val VERSION = 1

        fun default() = BotConfig(
            listOf(
                BotCommand("start", "Começar", "Olá, {nome}! Eu sou o {bot}. Use o menu / para ver o que sei fazer."),
                BotCommand("horario", "Horário de funcionamento", "Olá, {nome}! Funcionamos de 8h às 18h."),
            )
        )

        fun fromJson(json: JSONObject): BotConfig {
            val arr = json.optJSONArray("commands") ?: JSONArray()
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                BotCommand(
                    command = o.optString("command"),
                    description = o.optString("description"),
                    response = o.optString("response"),
                    adminOnly = o.optBoolean("admin_only", false),
                )
            }
            return BotConfig(list)
        }
    }
}

object ConfigRepository {
    fun file(context: Context) = File(context.filesDir, "config.json")

    fun load(context: Context): BotConfig {
        val f = file(context)
        if (!f.exists()) return BotConfig.default().also { save(context, it) }
        return try {
            BotConfig.fromJson(JSONObject(f.readText()))
        } catch (e: Exception) {
            BotConfig.default()
        }
    }

    /** Grava num arquivo temporário e renomeia, para o motor nunca ler pela metade. */
    fun save(context: Context, config: BotConfig) {
        val f = file(context)
        val tmp = File(f.parentFile, "config.json.tmp")
        tmp.writeText(config.toJson().toString(2))
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText())
            tmp.delete()
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
}
