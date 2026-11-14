package io.github.alerrandrov.easytelebot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.engine.BotRuntime
import io.github.alerrandrov.easytelebot.engine.ErrorExplainer
import io.github.alerrandrov.easytelebot.engine.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen(botId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val logs by BotRuntime.logs.collectAsStateWithLifecycle()
    val entries = logs[botId] ?: emptyList()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var expanded by remember { mutableStateOf<Long?>(null) }

    Scaffold(topBar = { AppBar(stringResource(R.string.log_title), onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.log_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { BotRuntime.clearLog(botId) }) { Text(stringResource(R.string.log_clear)) }
                }
            }
            if (entries.isEmpty()) {
                item { Text(stringResource(R.string.log_empty), Modifier.padding(vertical = 24.dp)) }
            }
            items(entries) { e ->
                val color = when (e.level) {
                    LogEntry.Level.ERROR -> MaterialTheme.colorScheme.error
                    LogEntry.Level.WARN -> MaterialTheme.colorScheme.tertiary
                    LogEntry.Level.IN -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
                val icon = when (e.level) {
                    LogEntry.Level.IN -> "⬇"
                    LogEntry.Level.OUT -> "⬆"
                    LogEntry.Level.ERROR -> "✖"
                    LogEntry.Level.WARN -> "!"
                    LogEntry.Level.INFO -> "•"
                }
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row {
                        Text(fmt.format(Date(e.time)), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(8.dp))
                        Text(icon, color = color)
                        Spacer(Modifier.width(8.dp))
                        Text(e.text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                    val err = e.error
                    if (err != null) {
                        if (expanded == e.time) {
                            val exp = ErrorExplainer.explain(context, err)
                            Column(Modifier.padding(start = 16.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(exp.message, style = MaterialTheme.typography.bodySmall)
                                StepList(exp.steps)
                            }
                        } else {
                            TextButton(onClick = { expanded = e.time }) { Text(stringResource(R.string.log_what_to_do)) }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
