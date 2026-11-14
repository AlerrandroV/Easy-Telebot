package io.github.alerrandrov.easytelebot

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import io.github.alerrandrov.easytelebot.service.Alerts
import io.github.alerrandrov.easytelebot.ui.AppRoot
import io.github.alerrandrov.easytelebot.ui.EasyTelebotTheme
import io.github.alerrandrov.easytelebot.ui.OpenRequest

class MainActivity : ComponentActivity() {
    /** Tela pedida por uma notificação (ex.: antispam de um bot). */
    private val openRequest = mutableStateOf<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readIntent(intent)
        setContent {
            EasyTelebotTheme {
                AppRoot(openRequest = openRequest.value, onOpenHandled = { openRequest.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent?) {
        val bot = intent?.getLongExtra(Alerts.EXTRA_BOT, 0L) ?: 0L
        val screen = intent?.getStringExtra(Alerts.EXTRA_SCREEN)
        if (bot != 0L && screen != null) openRequest.value = OpenRequest(bot, screen)
    }
}
