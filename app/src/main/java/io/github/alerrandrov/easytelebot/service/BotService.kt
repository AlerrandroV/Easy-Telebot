package io.github.alerrandrov.easytelebot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.chaquo.python.PyObject
import io.github.alerrandrov.easytelebot.MainActivity
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.ConfigRepository
import io.github.alerrandrov.easytelebot.data.Prefs
import io.github.alerrandrov.easytelebot.data.StorageRepository
import io.github.alerrandrov.easytelebot.data.TokenStore
import io.github.alerrandrov.easytelebot.engine.BotRuntime
import io.github.alerrandrov.easytelebot.engine.BotState
import io.github.alerrandrov.easytelebot.engine.EngineCallbacks
import io.github.alerrandrov.easytelebot.engine.ErrorExplainer
import io.github.alerrandrov.easytelebot.engine.LogEntry
import io.github.alerrandrov.easytelebot.engine.PythonBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Serviço em primeiro plano (`specialUse`) que mantém os motores Python rodando,
 * um por bot, cada um na sua thread. O serviço fica vivo enquanto houver algum bot ligado.
 */
class BotService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val threads = HashMap<Long, Thread>()
    private val restarting = HashSet<Long>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BotRuntime.onNotificationChange = { main.post { updateNotification() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val id = intent?.getLongExtra(EXTRA_BOT, 0L) ?: 0L
        when (intent?.action) {
            ACTION_START -> startBot(id)
            ACTION_STOP -> stopBot(id)
            ACTION_RESTART -> restartBot(id)
            ACTION_STOP_ALL -> BotRepository.bots.value.forEach { stopBot(it.id) }
            ACTION_START_AUTO -> BotRepository.bots.value.filter { it.shouldRun }.forEach { startBot(it.id) }
            null -> {
                // O sistema matou o processo e recriou o serviço (START_STICKY).
                val wanted = BotRepository.bots.value.filter { it.shouldRun }
                if (wanted.isNotEmpty()) Prefs(this).killedAt = System.currentTimeMillis()
                wanted.forEach {
                    BotRuntime.add(it.id, LogEntry.Level.WARN, getString(R.string.log_restarted_by_system))
                    startBot(it.id)
                }
            }
        }
        stopIfIdle()
        return START_STICKY
    }

    private fun startInForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun startBot(id: Long) {
        if (threads[id]?.isAlive == true) return
        val meta = BotRepository.get(id) ?: return
        val token = TokenStore.load(this, id)
        if (token == null) {
            BotRuntime.add(id, LogEntry.Level.ERROR, getString(R.string.log_no_token))
            BotRepository.update(id) { it.copy(shouldRun = false) }
            return
        }
        if (!meta.shouldRun) BotRepository.update(id) { it.copy(shouldRun = true) }
        if (!BotRepository.configFile(id).exists()) ConfigRepository.load(id)
        BotRuntime.markStarting(id)

        val callbacks = object : EngineCallbacks {
            override fun onEvent(json: String) = BotRuntime.handleEvent(id, json)
            override fun nowLocal(): String =
                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        }
        val configPath = BotRepository.configFile(id).absolutePath
        val dataDir = BotRepository.dir(id).absolutePath
        val storageJson = StorageRepository.engineJson(this, id)
        val thread = Thread({
            var engine: PyObject? = null
            try {
                engine = PythonBridge.createEngine(this, token, configPath, dataDir, callbacks, storageJson)
                BotRuntime.engines[id] = engine
                // Pode ter sido desligado enquanto o Python ainda carregava.
                if (BotRepository.get(id)?.shouldRun != false) engine.callAttr("run")
            } catch (t: Throwable) {
                BotRuntime.add(id, LogEntry.Level.ERROR, getString(R.string.log_engine_crash, t.message ?: t.toString()))
            } finally {
                if (engine != null) BotRuntime.engines.remove(id, engine)
                main.post { onEngineFinished(id, Thread.currentThread()) }
            }
        }, "bot-$id")
        threads[id] = thread
        thread.start()
        registerNetworkCallback()
        updateNotification()
    }

    private fun stopBot(id: Long) {
        BotRepository.update(id) { it.copy(shouldRun = false) }
        BotRuntime.engines[id]?.let { e -> Thread { runCatching { e.callAttr("stop") } }.start() }
        BotRuntime.markStopped(id)
        updateNotification()
    }

    /** Token trocado: para o motor atual e sobe um novo. */
    private fun restartBot(id: Long) {
        val old = threads[id]
        restarting += id
        BotRuntime.engines[id]?.let { e -> runCatching { e.callAttr("stop") } }
        Thread {
            old?.join(10_000)
            main.post {
                restarting -= id
                if (threads[id] === old) threads.remove(id)
                startBot(id)
            }
        }.start()
    }

    private fun onEngineFinished(id: Long, thread: Thread) {
        if (threads[id] !== thread || id in restarting) return  // um motor novo vai assumir
        threads.remove(id)
        val status = BotRuntime.statusOf(id)
        // Token inválido etc.: não adianta tentar de novo sozinho.
        if (status.state == BotState.FATAL) BotRepository.update(id) { it.copy(shouldRun = false) }
        BotRuntime.markStopped(id)
        updateNotification()
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (restarting.isNotEmpty() || threads.values.any { it.isAlive }) return
        main.postDelayed({
            if (restarting.isEmpty() && threads.values.none { it.isAlive }) shutdown()
        }, 300)
    }

    /** Internet voltou: os motores tentam de novo agora em vez de esperar. */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                BotRuntime.engines.values.forEach { runCatching { it.callAttr("wake") } }
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        networkCallback = cb
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) }
        }
        networkCallback = null
    }

    private fun shutdown() {
        unregisterNetworkCallback()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        BotRuntime.engines.values.forEach { e -> Thread { runCatching { e.callAttr("stop") } }.start() }
        threads.keys.forEach { BotRuntime.markStopped(it) }
        unregisterNetworkCallback()
        BotRuntime.onNotificationChange = null
        super.onDestroy()
    }

    private fun updateNotification() {
        if (threads.isEmpty()) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val ids = threads.keys.toList()
        val statuses = ids.map { BotRuntime.statusOf(it) }
        val today = statuses.sumOf { it.messagesToday }
        val title = when {
            ids.isEmpty() -> getString(R.string.notif_starting)
            ids.size == 1 -> {
                val name = BotRepository.get(ids[0])?.username?.let { "@$it" } ?: getString(R.string.app_name)
                getString(R.string.notif_one_running, name, today)
            }
            else -> getString(R.string.notif_many_running, ids.size, today)
        }
        val problem = ids.zip(statuses).firstOrNull { (_, st) ->
            st.state == BotState.RETRYING || st.state == BotState.FATAL
        }
        val text = problem?.let { (id, st) ->
            val who = BotRepository.get(id)?.username?.let { "@$it: " } ?: ""
            who + (st.lastError?.let { ErrorExplainer.explain(this, it).title } ?: getString(R.string.state_retrying))
        } ?: getString(R.string.notif_all_ok)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BotService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop_all), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "bot_status"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_BOT = "bot_id"
        private const val ACTION_START = "io.github.alerrandrov.easytelebot.START"
        private const val ACTION_STOP = "io.github.alerrandrov.easytelebot.STOP"
        private const val ACTION_RESTART = "io.github.alerrandrov.easytelebot.RESTART"
        private const val ACTION_STOP_ALL = "io.github.alerrandrov.easytelebot.STOP_ALL"
        private const val ACTION_START_AUTO = "io.github.alerrandrov.easytelebot.START_AUTO"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notif_channel_desc) }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        private fun send(context: Context, action: String, id: Long = 0L) {
            ContextCompat.startForegroundService(
                context, Intent(context, BotService::class.java).setAction(action).putExtra(EXTRA_BOT, id),
            )
        }

        fun start(context: Context, id: Long) {
            BotRepository.update(id) { it.copy(shouldRun = true) }
            BotRuntime.lastStartRequest = System.currentTimeMillis()
            send(context, ACTION_START, id)
        }

        fun stop(context: Context, id: Long) {
            BotRepository.update(id) { it.copy(shouldRun = false) }
            if (BotRuntime.statusOf(id).running) send(context, ACTION_STOP, id)
        }

        /** Reinicia o motor (ex.: token trocado). Só faz algo se o bot estiver ligado. */
        fun restartIfRunning(context: Context, id: Long) {
            if (BotRuntime.statusOf(id).running) send(context, ACTION_RESTART, id)
        }

        /** Liga todos os bots marcados com "iniciar com o celular". */
        fun startAutoBots(context: Context) {
            val bots = BotRepository.bots.value.filter { it.autoStart }
            if (bots.isEmpty()) return
            bots.forEach { b -> BotRepository.update(b.id) { it.copy(shouldRun = true) } }
            send(context, ACTION_START_AUTO)
        }
    }
}
