package io.github.alerrandrov.easytelebot.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.engine.BotRuntime
import io.github.alerrandrov.easytelebot.engine.ErrorExplainer
import io.github.alerrandrov.easytelebot.engine.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class LogFilter(val label: Int, val levels: Set<LogEntry.Level>) {
    ALL(R.string.log_filter_all, LogEntry.Level.entries.toSet()),
    ERRORS(R.string.log_filter_errors, setOf(LogEntry.Level.ERROR)),
    WARNINGS(R.string.log_filter_warnings, setOf(LogEntry.Level.WARN, LogEntry.Level.ERROR)),
    IN(R.string.log_filter_in, setOf(LogEntry.Level.IN)),
    OUT(R.string.log_filter_out, setOf(LogEntry.Level.OUT)),
    SYSTEM(R.string.log_filter_system, setOf(LogEntry.Level.INFO)),
}

/** Uma linha da lista: a entrada e de qual bot ela é. */
private data class LogLine(val botId: Long, val entry: LogEntry)

/**
 * Logs: tudo o que os bots fizeram (mensagens, respostas, erros explicados, avisos),
 * guardado em arquivo. `botId = null` mostra todos os bots juntos.
 */
@Composable
fun LogScreen(botId: Long?, onBack: () -> Unit) {
    val context = LocalContext.current
    val logs by BotRuntime.logs.collectAsStateWithLifecycle()
    val bots by BotRepository.bots.collectAsStateWithLifecycle()
    val names = bots.associate { it.id to it.name.ifBlank { "@" + it.username } }
    LaunchedEffect(botId, bots.size) {
        withContext(Dispatchers.IO) {
            if (botId != null) BotRuntime.ensureLogsLoaded(botId) else bots.forEach { BotRuntime.ensureLogsLoaded(it.id) }
        }
    }
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf<LogLine?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val time = remember { SimpleDateFormat(context.getString(R.string.pattern_time_seconds), Locale.getDefault()) }
    val full = remember { SimpleDateFormat(context.getString(R.string.pattern_date_time_seconds), Locale.getDefault()) }

    val all = remember(logs, botId) {
        val ids = if (botId != null) listOf(botId) else logs.keys
        ids.flatMap { id -> (logs[id] ?: emptyList()).map { LogLine(id, it) } }.sortedByDescending { it.entry.time }
    }
    val lines = all.filter { l ->
        l.entry.level in filter.levels &&
            (query.isBlank() || l.entry.text.contains(query, ignoreCase = true) ||
                (l.entry.error?.code?.contains(query, ignoreCase = true) == true))
    }
    val counts = LogFilter.entries.associateWith { f -> all.count { it.entry.level in f.levels } }

    fun export() {
        val text = lines.asReversed().joinToString("\n") { l ->
            val bot = if (botId == null) "[" + (names[l.botId] ?: l.botId) + "] " else ""
            val err = l.entry.error?.let { " (${it.code}${if (it.detail.isNotBlank()) ": " + it.detail else ""})" } ?: ""
            "${full.format(Date(l.entry.time))} ${l.entry.level.name.padEnd(5)} $bot${l.entry.text}$err"
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.log_export_subject))
            .putExtra(Intent.EXTRA_TEXT, text.takeLast(200_000))
        safeStart(context, Intent.createChooser(send, context.getString(R.string.log_export)))
    }

    Scaffold(
        topBar = {
            AppBar(stringResource(if (botId == null) R.string.log_title_all else R.string.log_title), onBack) {
                IconButton(onClick = { export() }, enabled = lines.isNotEmpty()) {
                    AppIcon(R.drawable.ic_ios_share, description = stringResource(R.string.log_export))
                }
                IconButton(onClick = { confirmClear = true }, enabled = all.isNotEmpty()) {
                    AppIcon(R.drawable.ic_delete, description = stringResource(R.string.log_clear))
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Column(Modifier.padding(16.dp, 8.dp, 16.dp, 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.log_search)) },
                        leadingIcon = { AppIcon(R.drawable.ic_search) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { AppIcon(R.drawable.ic_close) }
                        },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(LogFilter.entries) { f ->
                        FilterChip(
                            selected = filter == f, onClick = { filter = f },
                            label = { Text(stringResource(f.label) + " (" + counts[f] + ")") },
                        )
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.log_intro_full),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp, 4.dp),
                )
            }
            if (lines.isEmpty()) {
                item {
                    Text(
                        stringResource(if (all.isEmpty()) R.string.log_empty else R.string.log_no_match),
                        Modifier.padding(16.dp, 24.dp),
                    )
                }
            }
            lines.forEachIndexed { i, l ->
                val prev = lines.getOrNull(i - 1)
                if (prev == null || !sameDay(prev.entry.time, l.entry.time)) {
                    item(key = "d$i") { DayLabel(l.entry.time) }
                }
                item(key = "e$i") {
                    LogRow(
                        line = l,
                        botName = if (botId == null) names[l.botId] ?: l.botId.toString() else null,
                        time = time.format(Date(l.entry.time)),
                        expanded = expanded == l,
                        onExpand = { expanded = if (expanded == l) null else l },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.log_clear_title)) },
            text = { Text(stringResource(R.string.log_clear_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    if (botId != null) BotRuntime.clearLog(botId) else bots.forEach { BotRuntime.clearLog(it.id) }
                    confirmClear = false
                }) { Text(stringResource(R.string.log_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val x = Calendar.getInstance().apply { timeInMillis = a }
    val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun DayLabel(time: Long) {
    Text(
        DateFormat.getDateInstance(DateFormat.FULL).format(Date(time)),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
    )
}

@Composable
private fun LogRow(line: LogLine, botName: String?, time: String, expanded: Boolean, onExpand: () -> Unit) {
    val context = LocalContext.current
    val e = line.entry
    val color = when (e.level) {
        LogEntry.Level.ERROR -> MaterialTheme.colorScheme.error
        LogEntry.Level.WARN -> MaterialTheme.colorScheme.tertiary
        LogEntry.Level.IN -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val icon = when (e.level) {
        LogEntry.Level.IN -> R.drawable.ic_south
        LogEntry.Level.OUT -> R.drawable.ic_north
        LogEntry.Level.ERROR -> R.drawable.ic_error
        LogEntry.Level.WARN -> R.drawable.ic_warning
        LogEntry.Level.INFO -> R.drawable.ic_info
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(time, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            AppIcon(icon, Modifier.size(18.dp), tint = color)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                if (botName != null) {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(botName, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp))
                    }
                }
                Text(e.text, color = color, style = MaterialTheme.typography.bodyMedium)
            }
        }
        val err = e.error
        if (err != null) {
            if (expanded) {
                val exp = ErrorExplainer.explain(context, err)
                Box(Modifier.padding(start = 32.dp, top = 4.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(exp.title, style = MaterialTheme.typography.titleSmall)
                        Text(exp.message, style = MaterialTheme.typography.bodySmall)
                        StepList(exp.steps)
                        Text(stringResource(R.string.log_error_code, err.code), style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            TextButton(onClick = onExpand, modifier = Modifier.padding(start = 24.dp)) {
                Text(stringResource(if (expanded) R.string.log_hide else R.string.log_what_to_do))
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}
