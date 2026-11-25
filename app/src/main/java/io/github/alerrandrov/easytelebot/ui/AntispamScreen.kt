package io.github.alerrandrov.easytelebot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.AntispamSettings
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.ConfigRepository
import io.github.alerrandrov.easytelebot.data.TokenStore
import io.github.alerrandrov.easytelebot.engine.AntispamBridge
import io.github.alerrandrov.easytelebot.engine.BotRuntime
import io.github.alerrandrov.easytelebot.engine.SpamBan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Antispam: configuração + casos bloqueados esperando a decisão de um admin.
 * Cada caso mostra o motivo e as últimas mensagens; dá para abrir a conversa
 * inteira no mensageiro antes de decidir.
 */
@Composable
fun AntispamScreen(botId: Long, onOpenChat: (Long) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dir = remember { BotRepository.dir(botId).path }
    val versions by BotRuntime.bansVersion.collectAsStateWithLifecycle()
    val bans by produceState<List<SpamBan>?>(null, versions[botId]) {
        value = withContext(Dispatchers.IO) { AntispamBridge.list(context, dir) }
    }
    var config by remember { mutableStateOf(ConfigRepository.load(botId)) }
    var busy by remember { mutableStateOf<Long?>(null) }
    var confirmRelease by remember { mutableStateOf<SpamBan?>(null) }

    fun save(change: (AntispamSettings) -> AntispamSettings) {
        config = ConfigRepository.load(botId).let { it.copy(antispam = change(it.antispam)) }
        ConfigRepository.save(botId, config)
    }

    fun decide(ban: SpamBan, action: String) {
        busy = ban.userId
        scope.launch {
            withContext(Dispatchers.IO) {
                AntispamBridge.decide(context, TokenStore.load(context, botId), dir, botId, ban.userId, action)
            }
            busy = null
        }
    }

    Scaffold(contentWindowInsets = screenInsets, topBar = { AppBar(stringResource(R.string.antispam_title), onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.antispam_intro), style = MaterialTheme.typography.bodyMedium)
            }

            // Casos
            val list = bans
            val pending = list?.filter { it.pending }.orEmpty()
            val kept = list?.filter { !it.pending }.orEmpty()
            item { SectionTitle(stringResource(R.string.antispam_pending_title, pending.size)) }
            if (list != null && pending.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.antispam_pending_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(pending, key = { "p${it.userId}" }) { ban ->
                BanCard(ban, busy == ban.userId, onOpenChat, onRelease = { confirmRelease = ban }, onKeep = { decide(ban, AntispamBridge.KEEP) })
            }
            if (kept.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.antispam_kept_title, kept.size)) }
                items(kept, key = { "k${it.userId}" }) { ban ->
                    BanCard(ban, busy == ban.userId, onOpenChat, onRelease = { confirmRelease = ban }, onKeep = null)
                }
            }

            // Configuração
            item { SectionTitle(stringResource(R.string.antispam_settings)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        SwitchRow(
                            title = stringResource(R.string.antispam_enable),
                            subtitle = stringResource(R.string.antispam_enable_sub),
                            checked = config.antispam.enabled,
                        ) { v -> save { it.copy(enabled = v) } }
                        if (config.antispam.enabled) {
                            HorizontalDivider()
                            SensitivityPicker(config.antispam.sensitivity) { v -> save { it.copy(sensitivity = v) } }
                            HorizontalDivider()
                            SwitchRow(
                                title = stringResource(R.string.antispam_warn_user),
                                subtitle = stringResource(R.string.antispam_warn_user_sub),
                                checked = config.antispam.warnUser,
                            ) { v -> save { it.copy(warnUser = v) } }
                            HorizontalDivider()
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.antispam_level), style = MaterialTheme.typography.titleSmall)
                                LevelPicker(config.antispam.adminLevel, explain = { l -> adminClassExplanationShort(l) }) { l ->
                                    save { it.copy(adminLevel = l) }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.antispam_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    confirmRelease?.let { ban ->
        AlertDialog(
            onDismissRequest = { confirmRelease = null },
            title = { Text(stringResource(R.string.antispam_release_title, ban.displayName)) },
            text = {
                Text(stringResource(if (ban.isPrivate) R.string.antispam_release_msg else R.string.antispam_release_msg_group))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRelease = null
                    decide(ban, AntispamBridge.RELEASE)
                }) { Text(stringResource(R.string.antispam_release)) }
            },
            dismissButton = { TextButton(onClick = { confirmRelease = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SensitivityPicker(value: String, onChange: (String) -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.antispam_sensitivity), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AntispamSettings.LEVELS.forEach { l ->
                FilterChip(selected = l == value, onClick = { onChange(l) }, label = { Text(sensitivityLabel(l)) })
            }
        }
        Text(
            when (value) {
                "baixa" -> stringResource(R.string.antispam_sens_low_explain)
                "alta" -> stringResource(R.string.antispam_sens_high_explain)
                else -> stringResource(R.string.antispam_sens_medium_explain)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Motivo do bloqueio, legível. */
@Composable
fun banReason(ban: SpamBan): String =
    if (ban.reason == "manual") stringResource(R.string.antispam_reason_manual) else ban.reasonText

@Composable
private fun sensitivityLabel(level: String) = when (level) {
    "baixa" -> stringResource(R.string.antispam_sens_low)
    "alta" -> stringResource(R.string.antispam_sens_high)
    else -> stringResource(R.string.antispam_sens_medium)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BanCard(
    ban: SpamBan,
    busy: Boolean,
    onOpenChat: (Long) -> Unit,
    onRelease: () -> Unit,
    onKeep: (() -> Unit)?,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = if (ban.pending) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ban.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(ban.username.takeIf { it.isNotEmpty() }?.let { "@$it" }, "ID ${ban.userId}").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (ban.pending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    contentColor = if (ban.pending) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondary,
                ) {
                    Text(
                        stringResource(if (ban.pending) R.string.antispam_status_pending else R.string.antispam_status_kept),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
            IconLabel(R.drawable.ic_warning, banReason(ban))
            IconLabel(
                R.drawable.ic_schedule,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ban.at)) +
                    if (!ban.isPrivate && ban.chatTitle.isNotEmpty()) " · " + stringResource(R.string.antispam_in_group, ban.chatTitle) else "",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!ban.pending && ban.decidedBy.isNotEmpty()) {
                Text(stringResource(R.string.antispam_decided_by, ban.decidedBy), style = MaterialTheme.typography.bodySmall)
            }
            if (ban.samples.isNotEmpty()) {
                Text(stringResource(R.string.antispam_samples), style = MaterialTheme.typography.labelLarge)
                ban.samples.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (ban.isPrivate) {
                    OutlinedButton(onClick = { onOpenChat(ban.userId) }) { Text(stringResource(R.string.antispam_open_chat)) }
                }
                Button(onClick = onRelease, enabled = !busy) { ButtonLabel(R.drawable.ic_check_circle, stringResource(R.string.antispam_release)) }
                if (onKeep != null) {
                    OutlinedButton(onClick = onKeep, enabled = !busy) { ButtonLabel(R.drawable.ic_block, stringResource(R.string.antispam_keep)) }
                }
            }
        }
    }
}
