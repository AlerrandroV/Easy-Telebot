package io.github.alerrandrov.easytelebot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.BotMeta
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.Prefs
import io.github.alerrandrov.easytelebot.engine.BotRuntime
import io.github.alerrandrov.easytelebot.engine.BotStatus
import io.github.alerrandrov.easytelebot.engine.EngineError
import io.github.alerrandrov.easytelebot.engine.ErrorExplainer
import io.github.alerrandrov.easytelebot.service.BatteryHelper

@Composable
fun HomeScreen(onAddBot: () -> Unit, onOpenBot: (Long) -> Unit, onBattery: () -> Unit, onLogs: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val bots by BotRepository.bots.collectAsStateWithLifecycle()
    val statuses by BotRuntime.status.collectAsStateWithLifecycle()
    val now = rememberNow()

    var killedAt by remember { mutableLongStateOf(prefs.killedAt) }
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumeTick++
        // Algum bot devia estar ligado e não está: o sistema matou o app.
        if (BotRuntime.looksKilled() && prefs.killedAt == 0L) prefs.killedAt = System.currentTimeMillis()
        killedAt = prefs.killedAt
    }
    val batteryOk = remember(resumeTick) { BatteryHelper.isIgnoringOptimizations(context) }

    Scaffold(
        topBar = {
            AppBar(stringResource(R.string.app_name)) {
                AppLanguageButton()
                if (bots.isNotEmpty()) {
                    androidx.compose.material3.IconButton(onClick = onLogs) {
                        AppIcon(R.drawable.ic_list_alt, description = stringResource(R.string.log_title_all))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (killedAt != 0L) {
                item {
                    ExplanationCard(ErrorExplainer.explain(context, EngineError("killed", ""))) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onBattery) { Text(stringResource(R.string.battery_open_guide)) }
                            TextButton(onClick = { killedAt = 0; prefs.killedAt = 0 }) {
                                Text(stringResource(R.string.dismiss))
                            }
                        }
                    }
                }
            }

            item { AddBotCard(onAddBot) }

            if (bots.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.home_no_bots),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(bots, key = { it.id }) { meta ->
                BotListCard(meta, statuses[meta.id] ?: BotRuntime.statusOf(meta.id), now) { onOpenBot(meta.id) }
            }

            item {
                SectionCard(
                    title = stringResource(R.string.home_battery),
                    subtitle = stringResource(if (batteryOk) R.string.home_battery_ok else R.string.home_battery_bad),
                    onClick = onBattery,
                )
            }
            item {
                Text(
                    stringResource(R.string.home_limitation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddBotCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(R.string.home_add_bot), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.home_add_bot_sub), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun BotListCard(meta: BotMeta, status: BotStatus, now: Long, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BotAvatar(meta)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    meta.name.ifEmpty { "@${meta.username}" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.username.isNotEmpty()) {
                    Text(
                        "@${meta.username}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusLine(status, now)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


/** Botão do idioma do app (pt-BR / en-US / igual ao sistema). */
@Composable
private fun AppLanguageButton() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val current = remember(open) { AppLanguages.current(context) }
    androidx.compose.foundation.layout.Box {
        androidx.compose.material3.IconButton(onClick = { open = true }) {
            AppIcon(R.drawable.ic_translate, description = stringResource(R.string.app_language))
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val options = listOf("" to stringResource(R.string.app_language_system)) +
                AppLanguages.ALL.map { it.code to stringResource(it.label) }
            options.forEach { (tag, label) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { if (tag.equals(current, ignoreCase = true)) AppIcon(R.drawable.ic_check) },
                    onClick = {
                        open = false
                        (context as? android.app.Activity)?.let { AppLanguages.set(it, tag) }
                    },
                )
            }
        }
    }
}
