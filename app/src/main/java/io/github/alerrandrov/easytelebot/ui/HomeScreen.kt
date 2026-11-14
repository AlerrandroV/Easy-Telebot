package io.github.alerrandrov.easytelebot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.ConfigRepository
import io.github.alerrandrov.easytelebot.data.Prefs
import io.github.alerrandrov.easytelebot.data.TokenStore
import io.github.alerrandrov.easytelebot.engine.BotRuntime
import io.github.alerrandrov.easytelebot.engine.BotState
import io.github.alerrandrov.easytelebot.engine.EngineError
import io.github.alerrandrov.easytelebot.engine.ErrorAction
import io.github.alerrandrov.easytelebot.engine.ErrorExplainer
import io.github.alerrandrov.easytelebot.engine.PythonBridge
import io.github.alerrandrov.easytelebot.service.BatteryHelper
import io.github.alerrandrov.easytelebot.service.BotService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(navigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val status by BotRuntime.status.collectAsStateWithLifecycle()

    var startOnBoot by remember { mutableStateOf(prefs.startOnBoot) }
    var killedAt by remember { mutableLongStateOf(prefs.killedAt) }
    var webhookMessage by remember { mutableStateOf<String?>(null) }

    // Atualiza o que depende do sistema (bateria, morte do serviço) ao voltar para o app.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumeTick++
        // O usuário queria o bot ligado, mas o serviço não está rodando: o sistema matou o app.
        if (BotRuntime.looksKilled(prefs) && prefs.killedAt == 0L) {
            prefs.killedAt = System.currentTimeMillis()
        }
        killedAt = prefs.killedAt
    }

    val batteryOk = remember(resumeTick) { BatteryHelper.isIgnoringOptimizations(context) }
    val commandsCount = remember(resumeTick) { ConfigRepository.load(context).commands.size }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        BotService.start(context)
    }

    fun startBot() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            BotService.start(context)
        }
    }

    Scaffold(topBar = { AppBar(stringResource(R.string.app_name)) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (killedAt != 0L) {
                ExplanationCard(ErrorExplainer.explain(context, EngineError("killed", ""))) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { navigate(Screen.BATTERY) }) { Text(stringResource(R.string.battery_open_guide)) }
                        TextButton(onClick = { killedAt = 0; prefs.killedAt = 0 }) { Text(stringResource(R.string.dismiss)) }
                    }
                }
            }

            BotCard(
                name = prefs.botName,
                username = prefs.botUsername,
                photo = prefs.botPhoto,
                state = status.state,
                running = status.serviceRunning,
                messagesToday = status.messagesToday,
                onStart = { startBot() },
                onStop = { BotService.stop(context) },
            )

            val err = status.lastError
            if (err != null && status.state != BotState.RUNNING) {
                val exp = ErrorExplainer.explain(context, err)
                ExplanationCard(exp) {
                    when (exp.action) {
                        ErrorAction.NEW_TOKEN -> Button(onClick = { navigate(Screen.TOKEN) }) {
                            Text(stringResource(R.string.token_change))
                        }
                        ErrorAction.DELETE_WEBHOOK -> {
                            Button(onClick = {
                                scope.launch {
                                    val token = TokenStore.load(context) ?: return@launch
                                    val e = withContext(Dispatchers.IO) { PythonBridge.deleteWebhook(context, token) }
                                    webhookMessage = if (e == null) {
                                        context.getString(R.string.webhook_removed)
                                    } else {
                                        ErrorExplainer.explain(context, e).title
                                    }
                                }
                            }) { Text(stringResource(R.string.webhook_remove)) }
                            webhookMessage?.let { Text(it) }
                        }
                        ErrorAction.BATTERY_GUIDE -> Button(onClick = { navigate(Screen.BATTERY) }) {
                            Text(stringResource(R.string.battery_open_guide))
                        }
                        ErrorAction.NONE -> {}
                    }
                }
            }

            SectionCard(
                title = stringResource(R.string.home_commands),
                subtitle = stringResource(R.string.home_commands_sub, commandsCount),
                onClick = { navigate(Screen.COMMANDS) },
            )
            SectionCard(
                title = stringResource(R.string.home_log),
                subtitle = stringResource(R.string.home_log_sub),
                onClick = { navigate(Screen.LOG) },
            )
            SectionCard(
                title = stringResource(R.string.home_battery),
                subtitle = stringResource(if (batteryOk) R.string.home_battery_ok else R.string.home_battery_bad),
                onClick = { navigate(Screen.BATTERY) },
            )
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.home_boot), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.home_boot_sub),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = startOnBoot, onCheckedChange = { startOnBoot = it; prefs.startOnBoot = it })
                }
            }
            SectionCard(
                title = stringResource(R.string.token_change),
                subtitle = stringResource(R.string.token_change_sub),
                onClick = { navigate(Screen.TOKEN) },
            )
            Text(
                stringResource(R.string.home_limitation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BotCard(
    name: String,
    username: String,
    photo: String?,
    state: BotState,
    running: Boolean,
    messagesToday: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val (label, color) = when {
        !running && state == BotState.FATAL -> stringResource(R.string.state_fatal) to Color(0xFFD32F2F)
        !running -> stringResource(R.string.state_stopped) to Color(0xFF9E9E9E)
        state == BotState.RUNNING -> stringResource(R.string.state_running) to Color(0xFF2E7D32)
        state == BotState.RETRYING -> stringResource(R.string.state_retrying) to Color(0xFFF9A825)
        state == BotState.FATAL -> stringResource(R.string.state_fatal) to Color(0xFFD32F2F)
        else -> stringResource(R.string.state_starting) to Color(0xFFF9A825)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BotAvatar(name, photo)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name.ifEmpty { stringResource(R.string.app_name) }, style = MaterialTheme.typography.titleLarge)
                    if (username.isNotEmpty()) Text("@$username", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
            if (running) {
                Text(
                    stringResource(R.string.home_messages_today, messagesToday),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (running) {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bot_stop))
                }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(),
                ) { Text(stringResource(R.string.bot_start)) }
            }
        }
    }
}
