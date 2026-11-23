package io.github.alerrandrov.easytelebot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.BotMessage
import io.github.alerrandrov.easytelebot.data.ButtonTypes
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.ConfigRepository
import io.github.alerrandrov.easytelebot.data.TokenStore
import io.github.alerrandrov.easytelebot.data.Variables
import io.github.alerrandrov.easytelebot.engine.AudienceCounts
import io.github.alerrandrov.easytelebot.engine.BotRuntime
import io.github.alerrandrov.easytelebot.engine.BroadcastBridge
import io.github.alerrandrov.easytelebot.engine.BroadcastJob
import io.github.alerrandrov.easytelebot.engine.EngineError
import io.github.alerrandrov.easytelebot.engine.ErrorExplainer
import io.github.alerrandrov.easytelebot.engine.PythonBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.random.Random

@Composable
fun BroadcastScreen(botId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dir = remember { BotRepository.dir(botId).path }
    val token = remember { TokenStore.load(context, botId) }
    val versions by BotRuntime.chatVersion.collectAsStateWithLifecycle()

    var config by remember { mutableStateOf(ConfigRepository.load(botId)) }
    var audience by remember { mutableStateOf(AudienceCounts(0, 0, 0)) }
    LaunchedEffect(versions[botId]) {
        audience = withContext(Dispatchers.IO) { runCatching { BroadcastBridge.audience(context, dir) }.getOrDefault(audience) }
    }
    var users by remember { mutableStateOf(true) }
    var groups by remember { mutableStateOf(true) }
    var channels by remember { mutableStateOf(true) }
    var nextKey by remember { mutableLongStateOf(1L) }
    var drafts by remember { mutableStateOf(listOf(Draft(0, BotMessage()))) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<EngineError?>(null) }
    var job by remember { mutableStateOf<BroadcastJob?>(null) }
    var pollTick by remember { mutableIntStateOf(0) }

    // Acompanha o envio (do app ou de um /post pelo Telegram) enquanto a tela estiver aberta.
    LaunchedEffect(pollTick) {
        while (true) {
            val j = withContext(Dispatchers.IO) { runCatching { BroadcastBridge.status(context, dir) }.getOrNull() }
            job = j
            if (j?.running != true) break
            delay(1000)
        }
    }

    val selectedTotal = (if (users) audience.users else 0) + (if (groups) audience.groups else 0) +
        (if (channels) audience.channels else 0)
    val messages = drafts.map { it.msg }.filter { it.text.isNotBlank() || it.media != null }
    val buttonsOk = messages.all { m -> m.buttons.flatten().all { it.text.isNotBlank() && ButtonTypes.isValid(it) && it.type != ButtonTypes.NODE } }
    val canSend = token != null && messages.isNotEmpty() && selectedTotal > 0 && buttonsOk && job?.running != true

    fun saveBroadcast(change: (io.github.alerrandrov.easytelebot.data.BroadcastSettings) -> io.github.alerrandrov.easytelebot.data.BroadcastSettings) {
        config = config.copy(broadcast = change(config.broadcast))
        ConfigRepository.save(botId, config)
    }

    fun send() {
        val t = token ?: return
        val payload = JSONObject().apply {
            put("targets", JSONArray().apply {
                if (users) put("users")
                if (groups) put("groups")
                if (channels) put("channels")
            })
            put("messages", JSONArray().apply {
                messages.forEach { m ->
                    put(JSONObject().apply {
                        put("text", m.text)
                        put("link_preview", m.linkPreview)
                        m.media?.let { put("media", JSONObject().put("type", it.type).put("file", it.file).put("name", it.name)) }
                        put("buttons", JSONArray().apply {
                            m.buttons.forEach { row ->
                                put(JSONArray().apply { row.forEach { put(it.toJson()) } })
                            }
                        })
                    })
                }
            })
        }
        error = null
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { BroadcastBridge.start(context, t, dir, payload) }) {
                is PythonBridge.Result.Ok -> pollTick++
                is PythonBridge.Result.Failed -> error = r.error
            }
        }
    }

    Scaffold(topBar = { AppBar(stringResource(R.string.broadcast_title), onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.broadcast_intro), style = MaterialTheme.typography.bodyMedium) }

            job?.let { j -> item { JobCard(j, onCancel = { scope.launch(Dispatchers.IO) { BroadcastBridge.cancel(context, dir) } }) } }

            // Destinos
            item { SectionTitle(stringResource(R.string.broadcast_targets)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        TargetRow(R.drawable.ic_person, stringResource(R.string.broadcast_users, audience.users), users) { users = it }
                        HorizontalDivider()
                        TargetRow(R.drawable.ic_group, stringResource(R.string.broadcast_groups, audience.groups), groups) { groups = it }
                        HorizontalDivider()
                        TargetRow(R.drawable.ic_campaign, stringResource(R.string.broadcast_channels, audience.channels), channels) { channels = it }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.broadcast_targets_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Mensagens (mesmo editor dos comandos)
            item { SectionTitle(stringResource(R.string.broadcast_messages)) }
            itemsIndexed(drafts, key = { _, d -> d.key }) { index, draft ->
                MessageEditor(
                    botId = botId,
                    index = index,
                    count = drafts.size,
                    draft = draft,
                    onChange = { m -> drafts = drafts.map { if (it.key == draft.key) it.copy(msg = m) else it } },
                    onMove = { delta ->
                        val list = drafts.toMutableList()
                        val target = index + delta
                        if (target in list.indices) {
                            list.add(target, list.removeAt(index))
                            drafts = list
                        }
                    },
                    onRemove = { drafts = drafts.filter { it.key != draft.key } },
                    mediaFolder = "outbox",
                )
            }
            item {
                OutlinedButton(onClick = { drafts = drafts + Draft(nextKey, BotMessage()); nextKey++ }, modifier = Modifier.fillMaxWidth()) {
                    ButtonLabel(R.drawable.ic_add, stringResource(R.string.cmd_add_message))
                }
            }
            error?.let { e -> item { ExplanationCard(ErrorExplainer.explain(context, e)) } }
            item {
                Button(
                    onClick = {
                        typed = ""
                        confirm = Random.nextInt(1000, 10000).toString()
                    },
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth(),
                ) { ButtonLabel(R.drawable.ic_send, stringResource(R.string.broadcast_send, selectedTotal)) }
            }
            if (!buttonsOk) {
                item { Text(stringResource(R.string.broadcast_bad_buttons), color = MaterialTheme.colorScheme.error) }
            }

            // Pelo Telegram
            item { SectionTitle(stringResource(R.string.broadcast_telegram)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        SwitchRow(
                            title = stringResource(R.string.broadcast_telegram_enable, config.broadcast.triggers.joinToString(" ou ") { "/$it" }),
                            subtitle = stringResource(R.string.broadcast_telegram_enable_sub),
                            checked = config.broadcast.enabled,
                        ) { v -> saveBroadcast { it.copy(enabled = v) } }
                        if (config.broadcast.enabled) {
                            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.broadcast_level), style = MaterialTheme.typography.titleSmall)
                                LevelPicker(config.broadcast.adminLevel, explain = { l -> adminClassExplanationShort(l) }) { l ->
                                    saveBroadcast { it.copy(adminLevel = l) }
                                }
                                HorizontalDivider()
                                Text(stringResource(R.string.broadcast_how_title), style = MaterialTheme.typography.titleSmall)
                                StepList(stringArrayResource(R.array.broadcast_how_steps).toList())
                                Text(stringResource(R.string.broadcast_buttons_title), style = MaterialTheme.typography.titleSmall)
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                    Text(
                                        "[Site | https://exemplo.com] [Canal | @meucanal]\n[Grupo | t.me/meugrupo]",
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(10.dp),
                                    )
                                }
                                Text(stringResource(R.string.broadcast_buttons_help), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { code ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.broadcast_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.broadcast_confirm_msg, messages.size, selectedTotal))
                    Text(stringResource(R.string.broadcast_confirm_code), style = MaterialTheme.typography.bodyMedium)
                    Text(code, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.broadcast_confirm_type)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { confirm = null; send() }, enabled = typed == code) {
                    Text(stringResource(R.string.broadcast_confirm_send))
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
internal fun adminClassExplanationShort(level: Int): String =
    if (level == 1) stringResource(R.string.broadcast_level_top)
    else stringResource(R.string.broadcast_level_explain, (1..level).joinToString(", ") { io.github.alerrandrov.easytelebot.data.AdminLevels.roman(it) })

@Composable
private fun TargetRow(icon: Int, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        IconLabel(icon, label, style = MaterialTheme.typography.bodyLarge, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun JobCard(job: BroadcastJob, onCancel: () -> Unit) {
    val title = when (job.status) {
        "running" -> stringResource(R.string.broadcast_job_running)
        "done" -> stringResource(R.string.broadcast_job_done)
        "cancelled" -> stringResource(R.string.broadcast_job_cancelled)
        else -> stringResource(R.string.broadcast_job_interrupted)
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (job.running) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val done = job.sent + job.failed
            LinearProgressIndicator(
                progress = { if (job.total > 0) done.toFloat() / job.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.broadcast_job_counts, job.sent, job.total, job.failed))
            if (job.unreachable > 0) Text(stringResource(R.string.broadcast_job_unreachable, job.unreachable), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(
                    if (job.source == "telegram") R.string.broadcast_job_from_telegram else R.string.broadcast_job_from_app,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(job.startedAt)),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (job.running) {
                Text(stringResource(R.string.broadcast_keep_open), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.broadcast_stop)) }
            }
        }
    }
}
