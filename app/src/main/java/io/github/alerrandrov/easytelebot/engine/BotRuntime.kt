package io.github.alerrandrov.easytelebot.engine

import android.content.Context
import com.chaquo.python.PyObject
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.AdminLevels
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.Prefs
import io.github.alerrandrov.easytelebot.service.Alerts
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
    private const val MAX_LOG = LogStore.KEEP

    private lateinit var app: Context
    private lateinit var prefs: Prefs

    private val _status = MutableStateFlow<Map<Long, BotStatus>>(emptyMap())
    val status: StateFlow<Map<Long, BotStatus>> = _status.asStateFlow()

    private val _logs = MutableStateFlow<Map<Long, List<LogEntry>>>(emptyMap())
    val logs: StateFlow<Map<Long, List<LogEntry>>> = _logs.asStateFlow()

    /** Último admin adicionado por bot (nome + classe + quando), para a tela de admins reagir. */
    data class AdminAdded(val name: String, val level: Int, val at: Long)

    private val _adminAdded = MutableStateFlow<Map<Long, AdminAdded>>(emptyMap())
    val adminAdded: StateFlow<Map<Long, AdminAdded>> = _adminAdded.asStateFlow()

    /** Muda a cada mensagem nova (recebida ou enviada) de um bot, para as telas de conversa recarregarem. */
    private val _chatVersion = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val chatVersion: StateFlow<Map<Long, Long>> = _chatVersion.asStateFlow()

    fun bumpChats(id: Long) = _chatVersion.bump(id)

    /** Muda a cada bloqueio/decisão do antispam, para a tela de antispam recarregar. */
    private val _bansVersion = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val bansVersion: StateFlow<Map<Long, Long>> = _bansVersion.asStateFlow()

    fun bumpBans(id: Long) = _bansVersion.bump(id)

    /** Muda a cada venda/assinatura, para as telas da loja recarregarem. */
    private val _shopVersion = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val shopVersion: StateFlow<Map<Long, Long>> = _shopVersion.asStateFlow()

    fun bumpShop(id: Long) = _shopVersion.bump(id)

    /** Porta em que a API local do Termux está no ar, por bot (ausente = desligada). */
    private val _localApi = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val localApi: StateFlow<Map<Long, Int>> = _localApi.asStateFlow()

    private fun MutableStateFlow<Map<Long, Long>>.bump(id: Long) = update { it + (id to ((it[id] ?: 0L) + 1)) }

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

    fun markStopped(id: Long) {
        _localApi.update { it - id }
        markStoppedStatus(id)
    }

    private fun markStoppedStatus(id: Long) = updateStatus(id) {
        it.copy(running = false, state = if (it.state == BotState.FATAL) BotState.FATAL else BotState.STOPPED)
    }

    fun requestReload(id: Long) {
        engines[id]?.let { e -> Thread { runCatching { e.callAttr("request_reload") } }.start() }
    }

    /** Bots cujo histórico de logs já foi lido do arquivo. */
    private val loadedLogs = ConcurrentHashMap.newKeySet<Long>()

    /** Lê do arquivo os logs de antes (de quando o app foi fechado). Chame fora da thread principal. */
    fun ensureLogsLoaded(id: Long) {
        if (!loadedLogs.add(id)) return
        val old = LogStore.load(id)
        _logs.update { map -> map + (id to ((map[id] ?: emptyList()) + old).take(MAX_LOG)) }
    }

    fun clearLog(id: Long) {
        _logs.update { it - id }
        LogStore.clear(id)
    }

    fun add(id: Long, level: LogEntry.Level, text: String, error: EngineError? = null) {
        val entry = LogEntry(System.currentTimeMillis(), level, text, error)
        if (id !in loadedLogs) ensureLogsLoaded(id)
        _logs.update { map -> map + (id to (listOf(entry) + (map[id] ?: emptyList())).take(MAX_LOG)) }
        LogStore.append(id, entry)
    }

    private fun s(res: Int, vararg args: Any): String = app.getString(res, *args)

    private fun automationLabel(kind: String, event: String, label: String): String = when (kind) {
        "keyword" -> s(R.string.log_auto_keyword, label)
        "media" -> s(R.string.log_auto_media, s(
            when (label) {
                "photo" -> R.string.media_photo
                "video" -> R.string.media_video
                "animation" -> R.string.media_animation
                "voice" -> R.string.media_voice
                "audio" -> R.string.media_audio
                "video_note" -> R.string.media_video_note
                "document" -> R.string.media_document
                "sticker" -> R.string.media_sticker
                "location" -> R.string.media_location
                "contact" -> R.string.media_contact
                else -> R.string.media_poll
            }
        ))
        "start" -> s(R.string.log_auto_start, label)
        "event" -> s(
            when (event) {
                "join" -> R.string.event_join
                "leave" -> R.string.event_leave
                "join_request" -> R.string.event_join_request
                "reaction" -> R.string.event_reaction
                "bot_added" -> R.string.event_bot_added
                else -> R.string.event_bot_promoted
            }
        )
        else -> label
    }

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
            "replied" -> add(
                id, LogEntry.Level.OUT,
                if (e.optString("kind", "command") == "command") s(R.string.log_replied, e.optString("command"), e.optInt("messages", 1))
                else s(R.string.log_replied_auto, e.optString("command"), e.optInt("messages", 1)),
            )
            "automation" -> add(
                id, LogEntry.Level.IN,
                s(R.string.log_automation, automationLabel(e.optString("kind"), e.optString("event"), e.optString("label")), e.optString("user")),
            )
            "inline_query" -> add(id, LogEntry.Level.IN, s(R.string.log_inline, e.optString("user"), e.optString("query"), e.optInt("results")))
            "unknown_command" -> add(id, LogEntry.Level.WARN, s(R.string.log_unknown_command, e.optString("command")))
            "recovered" -> add(id, LogEntry.Level.INFO, s(R.string.log_recovered))
            "storage_ok" -> add(id, LogEntry.Level.INFO, s(R.string.log_storage_ok))
            "chat" -> bumpChats(id)
            "spam_blocked" -> {
                val name = e.optString("name")
                val reason = e.optString("reason_text")
                add(id, LogEntry.Level.WARN, s(R.string.log_spam_blocked, name, reason, e.optInt("admins_notified")))
                bumpBans(id)
                val botName = BotRepository.bots.value.firstOrNull { it.id == id }?.name ?: ""
                Alerts.spamBlocked(app, id, botName, e.optLong("user_id"), name, reason)
            }
            "order_created" -> add(id, LogEntry.Level.INFO, s(R.string.log_order_created, e.optString("order"), e.optString("buyer"), e.optString("total")))
            "sale" -> {
                val problems = e.optInt("problems") > 0
                add(
                    id, if (problems) LogEntry.Level.WARN else LogEntry.Level.INFO,
                    s(if (problems) R.string.log_sale_problem else R.string.log_sale, e.optString("order"), e.optString("buyer"), e.optString("total")),
                )
                bumpShop(id)
                val botName = BotRepository.bots.value.firstOrNull { it.id == id }?.name ?: ""
                Alerts.sale(app, id, botName, e.optString("order"), e.optString("buyer"), e.optString("total"), problems)
            }
            "subscription_expired" -> {
                add(
                    id, LogEntry.Level.INFO,
                    s(if (e.optBoolean("removed")) R.string.log_sub_expired else R.string.log_sub_expired_not_removed, e.optString("user"), e.optString("chat")),
                )
                bumpShop(id)
            }
            "local_api" -> {
                if (e.optString("state") == "listening") {
                    _localApi.update { it + (id to e.optInt("port")) }
                    add(id, LogEntry.Level.INFO, s(R.string.log_local_api_on, e.optInt("port")))
                } else {
                    _localApi.update { it - id }
                }
            }
            "flow_answer" -> add(
                id, LogEntry.Level.IN,
                s(R.string.log_flow_answer, e.optString("user"), e.optString("variable"), e.optString("node")),
            )
            "custom_callback" -> add(id, LogEntry.Level.WARN, s(R.string.log_custom_callback, e.optString("data")))
            "script_done" -> add(
                id, LogEntry.Level.OUT,
                s(R.string.log_script_done, e.optString("command"), e.optDouble("seconds").toString().replace('.', ','), e.optInt("messages")),
            )
            "feed_posted" -> add(id, LogEntry.Level.OUT, s(R.string.log_feed_posted, e.optString("label"), e.optString("title")))
            "schedule_fired" -> add(id, LogEntry.Level.OUT, s(R.string.log_schedule_fired, e.optString("label"), e.optInt("recipients")))
            "drip_sent" -> add(id, LogEntry.Level.OUT, s(R.string.log_drip_sent, e.optString("label"), e.optString("user")))
            "reminder_created" -> add(id, LogEntry.Level.INFO, s(R.string.log_reminder_created, e.optString("user"), e.optString("when")))
            "reminder_sent" -> add(id, LogEntry.Level.OUT, s(R.string.log_reminder_sent))
            "code_log" -> add(id, LogEntry.Level.INFO, s(R.string.log_code_log, e.optString("command"), e.optString("text")))
            "code_error" -> add(id, LogEntry.Level.ERROR, s(R.string.log_code_error, e.optString("command"), e.optString("message")))
            "spam_decided" -> {
                val released = e.optString("action") == "release"
                add(
                    id, LogEntry.Level.INFO,
                    s(if (released) R.string.log_spam_released else R.string.log_spam_kept, e.optString("name"), e.optString("by")),
                )
                bumpBans(id)
                Alerts.dismissSpam(app, id, e.optLong("user_id"))
            }
            "post_started" -> add(id, LogEntry.Level.INFO, s(R.string.log_post_started, e.optString("user")))
            "broadcast_started" -> add(id, LogEntry.Level.INFO, s(R.string.log_broadcast_started, e.optInt("total")))
            "broadcast_done" -> add(
                id, LogEntry.Level.INFO,
                s(R.string.log_broadcast_done, e.optInt("sent"), e.optInt("total"), e.optInt("failed")),
            )
            "membership" -> {
                val gone = e.optString("status") in setOf("left", "kicked")
                val name = e.optString("chat")
                add(
                    id, if (gone) LogEntry.Level.WARN else LogEntry.Level.INFO,
                    when {
                        e.optString("chat_type") == "private" && gone -> s(R.string.log_user_blocked, name)
                        e.optString("chat_type") == "private" -> s(R.string.log_user_unblocked, name)
                        gone -> s(R.string.log_removed_from, name)
                        else -> s(R.string.log_added_to, name)
                    },
                )
            }
            "admin_added" -> {
                val a = e.optJSONObject("admin") ?: return
                val name = a.optString("username").takeIf { it.isNotEmpty() }?.let { "@$it" } ?: a.optString("first_name")
                val level = a.optInt("level", 1)
                _adminAdded.update { it + (id to AdminAdded(name, level, System.currentTimeMillis())) }
                add(id, LogEntry.Level.INFO, s(R.string.log_admin_added, name, AdminLevels.roman(level)))
            }
            "denied" -> {
                val level = e.optInt("level", 0)
                add(
                    id, LogEntry.Level.WARN,
                    if (level > 0) s(R.string.log_denied_level, e.optString("user"), e.optString("command"),
                        AdminLevels.roman(level), AdminLevels.roman(e.optInt("required", 1)))
                    else s(R.string.log_denied, e.optString("user"), e.optString("command")),
                )
            }
            "newadmin_invalid" -> add(
                id, LogEntry.Level.WARN,
                when {
                    !e.optBoolean("private", true) -> s(R.string.log_newadmin_group, e.optString("user"))
                    e.optBoolean("had_code") -> s(R.string.log_newadmin_invalid, e.optString("user"))
                    else -> s(R.string.log_newadmin_empty, e.optString("user"))
                },
            )
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
