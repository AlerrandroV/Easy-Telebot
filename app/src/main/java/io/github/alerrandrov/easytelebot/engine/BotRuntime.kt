package io.github.alerrandrov.easytelebot.engine

import android.content.Context
import com.chaquo.python.PyObject
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

enum class BotState { STOPPED, STARTING, RUNNING, RETRYING, FATAL }

data class LogEntry(
    val time: Long,
    val level: Level,
    val text: String,
    val error: EngineError? = null,
) {
    enum class Level { INFO, IN, OUT, WARN, ERROR }
}

data class BotStatus(
    val state: BotState = BotState.STOPPED,
    /** Há um motor rodando para este bot. */
    val running: Boolean = false,
    /** Quando o bot foi ligado (para "Ligado há 2 h"). */
    val startedAt: Long = 0,
    val lastError: EngineError? = null,
    val messagesToday: Int = 0,
)

/**
 * Estado de todos os bots, compartilhado entre o serviço e as telas. Recebe os
 * eventos dos motores Python e os transforma em status + registro (log) legível.
 */
object BotRuntime {
    private const val MAX_LOG = 300

    private lateinit var app: Context
    private lateinit var prefs: Prefs

    private val _status = MutableStateFlow<Map<Long, BotStatus>>(emptyMap())
    val status: StateFlow<Map<Long, BotStatus>> = _status.asStateFlow()

    private val _logs = MutableStateFlow<Map<Long, List<LogEntry>>>(emptyMap())
    val logs: StateFlow<Map<Long, List<LogEntry>>> = _logs.asStateFlow()

    /** Motores Python ativos (para pedir recarga/parada de qualquer thread). */
    val engines = ConcurrentHashMap<Long, PyObject>()

    /** Chamado quando algo que aparece na notificação mudou. */
    @Volatile var onNotificationChange: (() -> Unit)? = null

    /** Quando o usuário pediu para ligar algum bot (para não confundir "ainda subindo" com "foi morto"). */
    @Volatile var lastStartRequest: Long = 0L

    fun init(context: Context) {
        app = context.applicationContext
        prefs = Prefs(app)
    }

    fun statusOf(id: Long): BotStatus =
        _status.value[id] ?: BotStatus(messagesToday = prefs.messagesToday(id))

    private fun updateStatus(id: Long, change: (BotStatus) -> BotStatus) {
        _status.update { map -> map + (id to change(map[id] ?: BotStatus(messagesToday = prefs.messagesToday(id)))) }
    }

    fun runningIds(): List<Long> = _status.value.filterValues { it.running }.keys.toList()

    /** Algum bot devia estar ligado, mas não está: o sistema matou o app. */
    fun looksKilled(): Boolean =
        System.currentTimeMillis() - lastStartRequest > 15_000 &&
            BotRepository.bots.value.any { it.shouldRun && !statusOf(it.id).running }

    fun markStarting(id: Long) = updateStatus(id) {
        it.copy(state = BotState.STARTING, running = true, startedAt = System.currentTimeMillis(), lastError = null)
    }

    fun markStopped(id: Long) = updateStatus(id) {
        it.copy(running = false, state = if (it.state == BotState.FATAL) BotState.FATAL else BotState.STOPPED)
    }

    fun requestReload(id: Long) {
        engines[id]?.let { e -> Thread { runCatching { e.callAttr("request_reload") } }.start() }
    }

    fun clearLog(id: Long) = _logs.update { it - id }

    fun add(id: Long, level: LogEntry.Level, text: String, error: EngineError? = null) {
        val entry = LogEntry(System.currentTimeMillis(), level, text, error)
        _logs.update { map -> map + (id to (listOf(entry) + (map[id] ?: emptyList())).take(MAX_LOG)) }
    }

    private fun s(res: Int, vararg args: Any): String = app.getString(res, *args)

    /** Evento JSON vindo de um motor Python (`Engine.emit`). Chamado na thread do motor. */
    fun handleEvent(id: Long, json: String) {
        val e = try { JSONObject(json) } catch (ex: Exception) { return }
        when (e.optString("type")) {
            "status" -> {
                val state = when (e.optString("state")) {
                    "starting" -> BotState.STARTING
                    "running" -> BotState.RUNNING
                    "retrying" -> BotState.RETRYING
                    "fatal" -> BotState.FATAL
                    else -> BotState.STOPPED
                }
                updateStatus(id) { it.copy(state = state, lastError = if (state == BotState.RUNNING) null else it.lastError) }
                when (state) {
                    BotState.RUNNING -> add(id, LogEntry.Level.INFO, s(R.string.log_running))
                    BotState.STOPPED -> add(id, LogEntry.Level.INFO, s(R.string.log_stopped))
                    else -> {}
                }
                onNotificationChange?.invoke()
            }
            "bot" -> {
                val bot = e.optJSONObject("bot") ?: return
                BotRepository.update(id) {
                    it.copy(name = bot.optString("first_name", it.name), username = bot.optString("username", it.username))
                }
                add(id, LogEntry.Level.INFO, s(R.string.log_connected, bot.optString("username")))
            }
            "config" -> add(id, LogEntry.Level.INFO, s(R.string.log_config, e.optInt("commands")))
            "received" -> {
                val n = prefs.incrementMessagesToday(id)
                updateStatus(id) { it.copy(messagesToday = n) }
                val chat = e.optString("chat")
                val who = e.optString("user")
                val text = e.optString("text")
                add(
                    id, LogEntry.Level.IN,
                    if (chat.isNotEmpty()) s(R.string.log_received_group, who, chat, text) else s(R.string.log_received, who, text),
                )
                onNotificationChange?.invoke()
            }
            "replied" -> add(id, LogEntry.Level.OUT, s(R.string.log_replied, e.optString("command"), e.optInt("messages", 1)))
            "unknown_command" -> add(id, LogEntry.Level.WARN, s(R.string.log_unknown_command, e.optString("command")))
            "recovered" -> add(id, LogEntry.Level.INFO, s(R.string.log_recovered))
            "debug_text" -> add(id, LogEntry.Level.WARN, s(R.string.log_debug_text, e.optString("text")))
            "error" -> {
                val err = EngineError.fromJson(e)
                val explanation = ErrorExplainer.explain(app, err)
                val ctx = e.optString("command").takeIf { it.isNotEmpty() }?.let { "/$it: " } ?: ""
                add(id, LogEntry.Level.ERROR, ctx + explanation.title, err)
                if (err.context == "getUpdates" || err.context == "engine") {
                    updateStatus(id) { it.copy(lastError = err) }
                }
                onNotificationChange?.invoke()
            }
        }
    }
}
