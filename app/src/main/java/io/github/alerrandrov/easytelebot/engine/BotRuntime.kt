package io.github.alerrandrov.easytelebot.engine

import android.content.Context
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

enum class BotState { STOPPED, STARTING, RUNNING, RETRYING, FATAL }

data class LogEntry(
    val time: Long,
    val level: Level,
    val text: String,
    val error: EngineError? = null,
) {
    enum class Level { INFO, IN, OUT, WARN, ERROR }
}

data class RuntimeStatus(
    val state: BotState = BotState.STOPPED,
    val serviceRunning: Boolean = false,
    val lastError: EngineError? = null,
    val messagesToday: Int = 0,
)

/**
 * Estado do bot compartilhado entre o serviço e as telas. Recebe os eventos do
 * motor Python e os transforma em status + registro (log) legível.
 */
object BotRuntime {
    private const val MAX_LOG = 300

    private lateinit var app: Context
    private lateinit var prefs: Prefs

    private val _status = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    /** Chamado quando algo que aparece na notificação mudou. */
    var onNotificationChange: (() -> Unit)? = null

    fun init(context: Context) {
        app = context.applicationContext
        prefs = Prefs(app)
        _status.update { it.copy(messagesToday = prefs.messagesToday()) }
    }

    fun setServiceRunning(running: Boolean) {
        _status.update {
            it.copy(
                serviceRunning = running,
                state = if (running) it.state else if (it.state == BotState.FATAL) BotState.FATAL else BotState.STOPPED,
            )
        }
    }

    /** Quando o usuário pediu para ligar (para não confundir "ainda subindo" com "foi morto"). */
    @Volatile var lastStartRequest: Long = 0L

    fun looksKilled(prefs: Prefs): Boolean =
        prefs.shouldRun && !_status.value.serviceRunning &&
            System.currentTimeMillis() - lastStartRequest > 15_000

    fun markStarting() {
        _status.update { it.copy(state = BotState.STARTING, lastError = null) }
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    fun add(level: LogEntry.Level, text: String, error: EngineError? = null) {
        val entry = LogEntry(System.currentTimeMillis(), level, text, error)
        _log.update { (listOf(entry) + it).take(MAX_LOG) }
    }

    private fun s(id: Int, vararg args: Any): String = app.getString(id, *args)

    /** Evento JSON vindo do motor Python (`Engine.emit`). Chamado na thread do motor. */
    fun handleEvent(json: String) {
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
                _status.update {
                    it.copy(state = state, lastError = if (state == BotState.RUNNING) null else it.lastError)
                }
                when (state) {
                    BotState.RUNNING -> add(LogEntry.Level.INFO, s(R.string.log_running))
                    BotState.STOPPED -> add(LogEntry.Level.INFO, s(R.string.log_stopped))
                    else -> {}
                }
                onNotificationChange?.invoke()
            }
            "bot" -> {
                val bot = e.optJSONObject("bot") ?: return
                prefs.botName = bot.optString("first_name")
                prefs.botUsername = bot.optString("username")
                add(LogEntry.Level.INFO, s(R.string.log_connected, bot.optString("username")))
            }
            "config" -> add(LogEntry.Level.INFO, s(R.string.log_config, e.optInt("commands")))
            "received" -> {
                val n = prefs.incrementMessagesToday()
                _status.update { it.copy(messagesToday = n) }
                val chat = e.optString("chat")
                val who = e.optString("user")
                val text = e.optString("text")
                add(
                    LogEntry.Level.IN,
                    if (chat.isNotEmpty()) s(R.string.log_received_group, who, chat, text)
                    else s(R.string.log_received, who, text),
                )
                onNotificationChange?.invoke()
            }
            "replied" -> add(LogEntry.Level.OUT, s(R.string.log_replied, e.optString("command")))
            "unknown_command" -> add(LogEntry.Level.WARN, s(R.string.log_unknown_command, e.optString("command")))
            "recovered" -> add(LogEntry.Level.INFO, s(R.string.log_recovered))
            "debug_text" -> add(LogEntry.Level.WARN, s(R.string.log_debug_text, e.optString("text")))
            "error" -> {
                val err = EngineError.fromJson(e)
                val explanation = ErrorExplainer.explain(app, err)
                val ctx = e.optString("command").takeIf { it.isNotEmpty() }?.let { "/$it: " } ?: ""
                add(LogEntry.Level.ERROR, ctx + explanation.title, err)
                if (err.context != "sendMessage" && err.context != "setMyCommands") {
                    _status.update { it.copy(lastError = err) }
                }
                onNotificationChange?.invoke()
            }
        }
    }
}
