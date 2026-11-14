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
import io.github.alerrandrov.easytelebot.data.ConfigRepository
import io.github.alerrandrov.easytelebot.data.Prefs
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
 * Serviço em primeiro plano (`specialUse`) que mantém o motor Python rodando.
 * O long polling acontece numa thread própria; parar = `engine.stop()`.
 */
class BotService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    @Volatile private var engine: PyObject? = null
    private var engineThread: Thread? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        BotRuntime.onNotificationChange = { main.post { updateNotification() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.shouldRun = false
            shutdown()
            return START_NOT_STICKY
        }

        startInForeground()

        if (intent?.action == ACTION_RESTART) {
            // Token trocado: para o motor atual e sobe um novo com o token novo.
            val old = engineThread
            engine?.callAttr("stop")
            Thread {
                old?.join(10_000)
                main.post {
                    engineThread = null
                    TokenStore.load(this)?.let { startEngine(it) }
                }
            }.start()
            return START_STICKY
        }

        // intent == null: o sistema matou o processo e recriou o serviço (START_STICKY).
        if (intent == null && prefs.shouldRun) {
            prefs.killedAt = System.currentTimeMillis()
            BotRuntime.add(LogEntry.Level.WARN, getString(R.string.log_restarted_by_system))
        }

        val token = TokenStore.load(this)
        if (token == null) {
            BotRuntime.add(LogEntry.Level.ERROR, getString(R.string.log_no_token))
            prefs.shouldRun = false
            shutdown()
            return START_NOT_STICKY
        }

        if (engineThread?.isAlive != true) startEngine(token)
        return START_STICKY
    }

    private fun startInForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        BotRuntime.setServiceRunning(true)
    }

    private fun startEngine(token: String) {
        BotRuntime.markStarting()
        val callbacks = object : EngineCallbacks {
            override fun onEvent(json: String) = BotRuntime.handleEvent(json)
            override fun nowLocal(): String =
                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        }
        val configPath = ConfigRepository.file(this).also {
            if (!it.exists()) ConfigRepository.load(this)
        }.absolutePath

        engineThread = Thread({
            var e: PyObject? = null
            try {
                e = PythonBridge.createEngine(this, token, configPath, callbacks)
                engine = e
                e.callAttr("run")
            } catch (t: Throwable) {
                BotRuntime.add(LogEntry.Level.ERROR, getString(R.string.log_engine_crash, t.message ?: t.toString()))
            } finally {
                if (engine === e) engine = null
                main.post { onEngineFinished() }
            }
        }, "bot-engine").apply { start() }

        registerNetworkCallback()
    }

    private fun onEngineFinished() {
        // Token inválido etc.: não adianta tentar de novo sozinho.
        if (BotRuntime.status.value.state == BotState.FATAL) prefs.shouldRun = false
        if (!prefs.shouldRun || BotRuntime.status.value.state == BotState.FATAL) shutdown()
    }

    /** Internet voltou: o motor tenta de novo agora em vez de esperar. */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                engine?.callAttr("wake")
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        networkCallback = cb
    }

    private fun shutdown() {
        engine?.callAttr("stop")
        networkCallback?.let {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
        }
        networkCallback = null
        BotRuntime.setServiceRunning(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        engine?.callAttr("stop")
        networkCallback?.let {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
        }
        networkCallback = null
        BotRuntime.onNotificationChange = null
        BotRuntime.setServiceRunning(false)
        super.onDestroy()
    }

    private fun updateNotification() {
        if (!BotRuntime.status.value.serviceRunning) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val st = BotRuntime.status.value
        val title = when (st.state) {
            BotState.RUNNING -> getString(R.string.notif_running, st.messagesToday)
            BotState.RETRYING -> getString(R.string.notif_retrying)
            BotState.FATAL -> getString(R.string.notif_fatal)
            else -> getString(R.string.notif_starting)
        }
        val text = st.lastError?.takeIf { st.state != BotState.RUNNING }
            ?.let { ErrorExplainer.explain(this, it).title }
            ?: prefs.botUsername.takeIf { it.isNotEmpty() }?.let { "@$it" }
            ?: getString(R.string.app_name)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "bot_status"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "io.github.alerrandrov.easytelebot.STOP"
        private const val ACTION_RESTART = "io.github.alerrandrov.easytelebot.RESTART"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notif_channel_desc) }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        fun start(context: Context) {
            Prefs(context).shouldRun = true
            BotRuntime.lastStartRequest = System.currentTimeMillis()
            ContextCompat.startForegroundService(context, Intent(context, BotService::class.java))
        }

        /** Reinicia o motor (ex.: token trocado). Só faz algo se o bot estiver ligado. */
        fun restartIfRunning(context: Context) {
            if (!BotRuntime.status.value.serviceRunning) return
            ContextCompat.startForegroundService(
                context, Intent(context, BotService::class.java).setAction(ACTION_RESTART),
            )
        }

        fun stop(context: Context) {
            Prefs(context).shouldRun = false
            if (BotRuntime.status.value.serviceRunning) {
                context.startService(Intent(context, BotService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
