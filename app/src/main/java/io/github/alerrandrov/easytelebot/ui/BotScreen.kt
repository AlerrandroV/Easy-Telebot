package io.github.alerrandrov.easytelebot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.BotExporter
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.ConfigRepository
import io.github.alerrandrov.easytelebot.data.SimpleCommand
import io.github.alerrandrov.easytelebot.data.StorageRepository
import io.github.alerrandrov.easytelebot.data.TokenStore
import io.github.alerrandrov.easytelebot.engine.AntispamBridge
import io.github.alerrandrov.easytelebot.engine.BotRuntime
import io.github.alerrandrov.easytelebot.engine.BotState
import io.github.alerrandrov.easytelebot.engine.ChatsBridge
import io.github.alerrandrov.easytelebot.engine.ShopBridge
import io.github.alerrandrov.easytelebot.engine.ErrorAction
import io.github.alerrandrov.easytelebot.engine.ErrorExplainer
import io.github.alerrandrov.easytelebot.engine.PythonBridge
import io.github.alerrandrov.easytelebot.service.BotService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotScreen(
    botId: Long,
    onBack: () -> Unit,
    /** (id do comando ou null para novo, novo já como comando de admin) */
    onEditCommand: (String?, Boolean) -> Unit,
    onProfile: () -> Unit,
    onLog: () -> Unit,
    /** true = já abrir o fluxo de adicionar admin */
    onAdmins: (Boolean) -> Unit,
    onStorage: () -> Unit,
    onChats: () -> Unit,
    onBroadcast: () -> Unit,
    onAntispam: () -> Unit,
    onShop: () -> Unit,
    onTermux: () -> Unit,
    onNewScriptCommand: () -> Unit,
    /** Novo produto (true = assinatura de canal). */
    onNewProduct: (Boolean) -> Unit,
    onChangeToken: () -> Unit,
    onBattery: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bots by BotRepository.bots.collectAsStateWithLifecycle()
    val statuses by BotRuntime.status.collectAsStateWithLifecycle()
    val meta = bots.firstOrNull { it.id == botId }
    if (meta == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val status = statuses[botId] ?: BotRuntime.statusOf(botId)
    val now = rememberNow()

    var config by remember { mutableStateOf(ConfigRepository.load(botId)) }
    val bansVersions by BotRuntime.bansVersion.collectAsStateWithLifecycle()
    val pending by produceState(0, bansVersions[botId]) {
        value = withContext(Dispatchers.IO) { AntispamBridge.countPending(context, BotRepository.dir(botId).path) }
    }
    var showAddSheet by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var includeToken by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var webhookMessage by remember { mutableStateOf<String?>(null) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        BotService.start(context, botId)
    }
    fun startBot() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            BotService.start(context, botId)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val withToken = includeToken
        scope.launch {
            val ok = withContext(Dispatchers.IO) { BotExporter.export(context, meta, uri, withToken) }
            Toast.makeText(
                context,
                context.getString(if (ok) R.string.export_done else R.string.export_failed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Scaffold(
        contentWindowInsets = screenInsets,
        topBar = { AppBar(meta.name.ifEmpty { "@${meta.username}" }, onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showAddSheet = true }) {
                ButtonLabel(R.drawable.ic_add, stringResource(R.string.bot_add))
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Cabeçalho: foto, nome, status e ligar/desligar.
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BotAvatar(meta, 64.dp)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(meta.name, style = MaterialTheme.typography.titleLarge)
                                if (meta.username.isNotEmpty()) {
                                    Text(
                                        "@${meta.username}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { openUrl(context, "https://t.me/${meta.username}") },
                                    )
                                }
                            }
                        }
                        StatusLine(status, now)
                        if (status.running) {
                            Text(
                                stringResource(R.string.home_messages_today, status.messagesToday),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            OutlinedButton(onClick = { BotService.stop(context, botId) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.bot_stop))
                            }
                        } else {
                            Button(onClick = { startBot() }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.bot_start))
                            }
                        }
                    }
                }
            }

            val err = status.lastError
            if (err != null && status.state != BotState.RUNNING) {
                item {
                    val exp = ErrorExplainer.explain(context, err)
                    ExplanationCard(exp) {
                        when (exp.action) {
                            ErrorAction.NEW_TOKEN -> Button(onClick = onChangeToken) { Text(stringResource(R.string.token_change)) }
                            ErrorAction.DELETE_WEBHOOK -> {
                                Button(onClick = {
                                    scope.launch {
                                        val token = TokenStore.load(context, botId) ?: return@launch
                                        val r = withContext(Dispatchers.IO) { PythonBridge.deleteWebhook(context, token) }
                                        webhookMessage = when (r) {
                                            is PythonBridge.Result.Ok -> context.getString(R.string.webhook_removed)
                                            is PythonBridge.Result.Failed -> ErrorExplainer.explain(context, r.error).title
                                        }
                                    }
                                }) { Text(stringResource(R.string.webhook_remove)) }
                                webhookMessage?.let { Text(it) }
                            }
                            ErrorAction.BATTERY_GUIDE -> Button(onClick = onBattery) {
                                Text(stringResource(R.string.battery_open_guide))
                            }
                            ErrorAction.NONE, ErrorAction.FILES_PERMISSION -> {}
                        }
                    }
                }
            }

            item {
                val chatVersions by BotRuntime.chatVersion.collectAsStateWithLifecycle()
                val unread by produceState(0, chatVersions[botId]) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { ChatsBridge.unreadTotal(context, BotRepository.dir(botId).path) }.getOrDefault(0)
                    }
                }
                SectionCard(
                    icon = R.drawable.ic_forum,
                    title = stringResource(R.string.chats_title),
                    subtitle = if (unread > 0) stringResource(R.string.bot_chats_unread, unread) else stringResource(R.string.bot_chats_sub),
                    onClick = onChats,
                )
            }

            item {
                SectionCard(
                    icon = R.drawable.ic_campaign,
                    title = stringResource(R.string.broadcast_title),
                    subtitle = stringResource(R.string.bot_broadcast_sub),
                    onClick = onBroadcast,
                )
            }

            item {
                SectionCard(
                    icon = R.drawable.ic_storefront,
                    title = stringResource(R.string.shop_title),
                    subtitle = when {
                        !config.shop.enabled -> stringResource(R.string.bot_shop_off)
                        else -> pluralStringResourceSafe(R.plurals.bot_shop_products, config.shop.products.size)
                    },
                    onClick = onShop,
                )
            }

            item {
                SectionCard(
                    icon = R.drawable.ic_terminal,
                    title = stringResource(R.string.termux_title),
                    subtitle = when {
                        config.termux.apiEnabled -> stringResource(R.string.bot_termux_api_on, config.termux.port)
                        config.commands.any { it.script != null } ->
                            stringResource(R.string.bot_termux_scripts, config.commands.count { it.script != null })
                        else -> stringResource(R.string.bot_termux_sub)
                    },
                    onClick = onTermux,
                )
            }

            item {
                SectionCard(
                    icon = R.drawable.ic_shield,
                    title = stringResource(R.string.antispam_title),
                    subtitle = when {
                        pending > 0 -> stringResource(R.string.bot_antispam_pending, pending)
                        config.antispam.enabled -> stringResource(R.string.bot_antispam_on)
                        else -> stringResource(R.string.bot_antispam_off)
                    },
                    onClick = onAntispam,
                )
            }

            item { SectionTitle(stringResource(R.string.bot_section_commands)) }
            if (config.commands.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.commands_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(config.commands, key = { it.id }) { cmd -> CommandCard(cmd) { onEditCommand(cmd.id, false) } }

            item { SectionTitle(stringResource(R.string.bot_section_settings)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        SwitchRow(
                            title = stringResource(R.string.bot_auto_menu),
                            subtitle = stringResource(R.string.bot_auto_menu_sub),
                            checked = config.autoMenu,
                        ) {
                            config = config.copy(autoMenu = it)
                            ConfigRepository.save(botId, config)
                        }
                        HorizontalDivider()
                        SwitchRow(
                            title = stringResource(R.string.home_boot),
                            subtitle = stringResource(R.string.home_boot_sub),
                            checked = meta.autoStart,
                        ) { v -> BotRepository.update(botId) { it.copy(autoStart = v) } }
                        HorizontalDivider()
                        BotLanguageRow(config.language) {
                            config = config.copy(language = it)
                            ConfigRepository.save(botId, config)
                        }
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.bot_profile), stringResource(R.string.bot_profile_sub), onProfile, icon = R.drawable.ic_smart_toy)
            }
            item {
                SectionCard(stringResource(R.string.admins_title), stringResource(R.string.bot_admins_sub), { onAdmins(false) }, icon = R.drawable.ic_admin_panel_settings)
            }
            item {
                val storageType = remember(meta.photoVersion) { StorageRepository.load(botId).type }
                SectionCard(
                    stringResource(R.string.storage_title),
                    stringResource(R.string.bot_storage_sub, storageTypeLabel(storageType)),
                    onStorage,
                    icon = R.drawable.ic_storage,
                )
            }
            item { SectionCard(stringResource(R.string.home_log), stringResource(R.string.home_log_sub), onLog, icon = R.drawable.ic_list_alt) }
            item {
                SectionCard(stringResource(R.string.bot_export), stringResource(R.string.bot_export_sub), { showExport = true }, icon = R.drawable.ic_ios_share)
            }
            item {
                SectionCard(stringResource(R.string.token_change), stringResource(R.string.token_change_sub), onChangeToken, icon = R.drawable.ic_key)
            }
            item {
                SectionCard(stringResource(R.string.bot_delete), stringResource(R.string.bot_delete_sub), { showDelete = true }, icon = R.drawable.ic_delete_forever)
            }
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.add_title), style = MaterialTheme.typography.titleLarge)
                SectionCard(
                    icon = R.drawable.ic_send,
                    title = stringResource(R.string.add_simple_command),
                    subtitle = stringResource(R.string.add_simple_command_sub),
                    onClick = {
                        showAddSheet = false
                        onEditCommand(null, false)
                    },
                )
                SectionCard(
                    icon = R.drawable.ic_admin_panel_settings,
                    title = stringResource(R.string.add_admin_command),
                    subtitle = stringResource(R.string.add_admin_command_sub),
                    onClick = {
                        showAddSheet = false
                        onEditCommand(null, true)
                    },
                )
                SectionCard(
                    icon = R.drawable.ic_terminal,
                    title = stringResource(R.string.add_script_command),
                    subtitle = stringResource(R.string.add_script_command_sub),
                    onClick = {
                        showAddSheet = false
                        onNewScriptCommand()
                    },
                )
                SectionCard(
                    icon = R.drawable.ic_sell,
                    title = stringResource(R.string.add_product),
                    subtitle = stringResource(R.string.add_product_sub),
                    onClick = {
                        showAddSheet = false
                        onNewProduct(false)
                    },
                )
                SectionCard(
                    icon = R.drawable.ic_card_membership,
                    title = stringResource(R.string.add_subscription),
                    subtitle = stringResource(R.string.add_subscription_sub),
                    onClick = {
                        showAddSheet = false
                        onNewProduct(true)
                    },
                )
                SectionCard(
                    icon = R.drawable.ic_campaign,
                    title = stringResource(R.string.broadcast_title),
                    subtitle = stringResource(R.string.bot_broadcast_sub),
                    onClick = {
                        showAddSheet = false
                        onBroadcast()
                    },
                )
                SectionCard(
                    icon = R.drawable.ic_group,
                    title = stringResource(R.string.add_admin),
                    subtitle = stringResource(R.string.add_admin_sub),
                    onClick = {
                        showAddSheet = false
                        onAdmins(true)
                    },
                )
                Text(
                    stringResource(R.string.add_more_soon),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text(stringResource(R.string.bot_export)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.export_explain))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { includeToken = !includeToken }) {
                        Checkbox(checked = includeToken, onCheckedChange = { includeToken = it })
                        Text(stringResource(R.string.export_include_token))
                    }
                    if (includeToken) {
                        Text(
                            stringResource(R.string.export_token_warning),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showExport = false
                    exportLauncher.launch(BotExporter.suggestedName(meta))
                }) { Text(stringResource(R.string.export_save)) }
            },
            dismissButton = { TextButton(onClick = { showExport = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.bot_delete_confirm_title, meta.name)) },
            text = { Text(stringResource(R.string.bot_delete_confirm_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    BotService.stop(context, botId)
                    ChatsBridge.close(context, BotRepository.dir(botId).path)
                    ShopBridge.close(context, BotRepository.dir(botId).path)
                    BotRepository.delete(context, botId)
                    BotRuntime.clearLog(botId)
                    onBack()
                }) { Text(stringResource(R.string.bot_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun CommandCard(cmd: SimpleCommand, onClick: () -> Unit) {
    val level = cmd.adminLevel
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (level != null) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cmd.triggers.joinToString("  ·  ") { "/$it" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (level != null) {
                    Spacer(Modifier.width(8.dp))
                    AdminBadge(level, prefix = stringResource(R.string.admin_badge_prefix))
                }
            }
            if (cmd.description.isNotBlank()) Text(cmd.description, style = MaterialTheme.typography.bodyMedium)
            val media = cmd.messages.count { it.media != null }
            val buttons = cmd.messages.sumOf { m -> m.buttons.sumOf { it.size } }
            val script = cmd.script
            if (script != null) IconLabel(
                R.drawable.ic_terminal, script.command,
                style = MaterialTheme.typography.bodySmall, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            ) else Text(
                listOfNotNull(
                    pluralStringResourceSafe(R.plurals.cmd_messages_count, cmd.messages.size),
                    media.takeIf { it > 0 }?.let { pluralStringResourceSafe(R.plurals.cmd_media_count, it) },
                    buttons.takeIf { it > 0 }?.let { pluralStringResourceSafe(R.plurals.cmd_buttons_count, it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            cmd.messages.firstOrNull { it.text.isNotBlank() }?.let {
                Text(
                    it.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun pluralStringResourceSafe(id: Int, count: Int): String =
    LocalContext.current.resources.getQuantityString(id, count, count)

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}


/** Idioma das mensagens automáticas do bot (loja, antispam, publicação...). */
@Composable
private fun BotLanguageRow(language: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = listOf("" to stringResource(R.string.bot_language_app)) + AppLanguages.ALL.map { it.code to stringResource(it.label) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(R.drawable.ic_translate, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.bot_language), style = MaterialTheme.typography.bodyLarge)
                Text(options.firstOrNull { it.first == language }?.second ?: options.first().second,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.bot_language_sub), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (code, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { if (code == language) AppIcon(R.drawable.ic_check) },
                    onClick = { open = false; onChange(code) },
                )
            }
        }
    }
}
