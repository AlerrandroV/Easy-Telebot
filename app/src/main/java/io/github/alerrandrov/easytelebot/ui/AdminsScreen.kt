package io.github.alerrandrov.easytelebot.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.AdminLevels
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.engine.BotRuntime
import io.github.alerrandrov.easytelebot.engine.PythonBridge
import io.github.alerrandrov.easytelebot.service.BotService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun AdminsScreen(botId: Long, autoAdd: Boolean = false, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bots by BotRepository.bots.collectAsStateWithLifecycle()
    val meta = bots.firstOrNull { it.id == botId } ?: return
    val statuses by BotRuntime.status.collectAsStateWithLifecycle()
    val running = statuses[botId]?.running == true
    val addedEvents by BotRuntime.adminAdded.collectAsStateWithLifecycle()
    val dir = remember { BotRepository.dir(botId).path }

    var reloadTick by remember { mutableIntStateOf(0) }
    val admins by produceState(emptyList<PythonBridge.Admin>(), reloadTick, addedEvents[botId]) {
        value = withContext(Dispatchers.IO) { PythonBridge.listAdmins(context, dir) }
    }

    var chooseLevel by remember { mutableStateOf(false) }
    var newLevel by remember { mutableIntStateOf(1) }
    var noLock by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<PythonBridge.AdminCode?>(null) }
    var codeCreatedAt by remember { mutableLongStateOf(0L) }
    var successName by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<PythonBridge.Admin?>(null) }

    // Quando alguém usar o código, o motor avisa: some o código e mostra o sucesso.
    val lastAdded = addedEvents[botId]
    LaunchedEffect(lastAdded) {
        if (lastAdded != null && code != null && lastAdded.at >= codeCreatedAt) {
            successName = context.getString(R.string.admin_success, lastAdded.name, AdminLevels.roman(lastAdded.level))
            code = null
        }
    }

    val askUnlock = rememberDeviceAuth { ok ->
        if (!ok) return@rememberDeviceAuth
        scope.launch {
            val c = withContext(Dispatchers.IO) { PythonBridge.createAdminCode(context, dir, newLevel) }
            successName = null
            codeCreatedAt = System.currentTimeMillis()
            code = c
        }
    }

    fun startAdd() {
        if (!isDeviceSecure(context)) {
            noLock = true
        } else {
            chooseLevel = true
        }
    }

    // Veio do botão "Adicionar" da tela do bot: já começa o fluxo.
    LaunchedEffect(Unit) { if (autoAdd) startAdd() }

    Scaffold(topBar = { AppBar(stringResource(R.string.admins_title), onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.admins_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            successName?.let { msg ->
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) { Text(msg, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
                }
            }

            val pending = code
            if (pending != null) {
                item {
                    CodeCard(
                        code = pending,
                        botUsername = meta.username,
                        botRunning = running,
                        onStartBot = { BotService.start(context, botId) },
                        onExpired = { code = null },
                        onCancel = {
                            scope.launch(Dispatchers.IO) { PythonBridge.cancelAdminCode(context, dir, pending.code) }
                            code = null
                        },
                    )
                }
            } else {
                item {
                    Button(onClick = { startAdd() }, modifier = Modifier.fillMaxWidth()) {
                        ButtonLabel(R.drawable.ic_add, stringResource(R.string.admins_add))
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.admins_list_title, admins.size)) }
            if (admins.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.admins_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(admins, key = { it.id }) { admin ->
                AdminCard(
                    admin = admin,
                    onLevel = { level ->
                        scope.launch {
                            withContext(Dispatchers.IO) { PythonBridge.setAdminLevel(context, dir, admin.id, level) }
                            BotRuntime.requestReload(botId)
                            reloadTick++
                        }
                    },
                    onRemove = { confirmRemove = admin },
                )
            }
        }
    }

    if (chooseLevel) {
        LevelDialog(
            initial = newLevel,
            onDismiss = { chooseLevel = false },
            onConfirm = { level ->
                chooseLevel = false
                newLevel = level
                askUnlock(
                    context.getString(R.string.admin_auth_title),
                    context.getString(R.string.admin_auth_subtitle, meta.name),
                )
            },
        )
    }

    if (noLock) {
        AlertDialog(
            onDismissRequest = { noLock = false },
            title = { Text(stringResource(R.string.admin_no_lock_title)) },
            text = { Text(stringResource(R.string.admin_no_lock_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    noLock = false
                    safeStart(context, Intent(Settings.ACTION_SECURITY_SETTINGS))
                }) { Text(stringResource(R.string.admin_open_security)) }
            },
            dismissButton = { TextButton(onClick = { noLock = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    confirmRemove?.let { admin ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.admin_remove_title, admin.displayName)) },
            text = { Text(stringResource(R.string.admin_remove_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    scope.launch {
                        withContext(Dispatchers.IO) { PythonBridge.removeAdmin(context, dir, admin.id) }
                        BotRuntime.requestReload(botId)
                        reloadTick++
                    }
                }) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun CodeCard(
    code: PythonBridge.AdminCode,
    botUsername: String,
    botRunning: Boolean,
    onStartBot: () -> Unit,
    onExpired: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val message = "/newadmin ${code.code}"
    val remaining by produceState((code.expiresAt - System.currentTimeMillis()) / 1000, code) {
        while (value > 0) {
            delay(1000)
            value = (code.expiresAt - System.currentTimeMillis()) / 1000
        }
        onExpired()
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.admin_code_title, AdminLevels.roman(code.level)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(stringResource(R.string.admin_code_steps, botUsername), style = MaterialTheme.typography.bodyMedium)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                SelectionContainer {
                    Text(message, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace)
                }
            }
            Text(
                stringResource(R.string.admin_code_expires, remaining.coerceAtLeast(0) / 60, remaining.coerceAtLeast(0) % 60),
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("newadmin", message))
                    Toast.makeText(context, context.getString(R.string.admin_code_copied), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.admin_code_copy)) }
                if (botUsername.isNotEmpty()) {
                    OutlinedButton(onClick = { openUrl(context, "https://t.me/$botUsername") }) {
                        Text(stringResource(R.string.admin_code_open_bot))
                    }
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.admin_code_cancel)) }
            }
            if (!botRunning) {
                Text(
                    stringResource(R.string.admin_code_bot_off),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onStartBot) { Text(stringResource(R.string.bot_start)) }
            }
            Text(
                stringResource(R.string.admin_code_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AdminCard(admin: PythonBridge.Admin, onLevel: (Int) -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(admin.displayName.ifEmpty { "ID ${admin.id}" }, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(admin.username.takeIf { it.isNotEmpty() }?.let { "@$it" }, "ID ${admin.id}").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AdminBadge(admin.level, prefix = stringResource(R.string.admin_class_short))
            }
            Text(
                stringResource(R.string.admin_added_on, DateFormat.getDateInstance().format(Date(admin.addedAt))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                Box {
                    TextButton(onClick = { menu = true }) { Text(stringResource(R.string.admin_change_class)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        (1..AdminLevels.MAX).forEach { level ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.admin_class_n, AdminLevels.roman(level))) },
                                onClick = {
                                    menu = false
                                    if (level != admin.level) onLevel(level)
                                },
                            )
                        }
                    }
                }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** Selo que diferencia admins/comandos de admin: "Classe III". */
@Composable
fun AdminBadge(level: Int, prefix: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary,
        ),
    ) {
        Text(
            "$prefix ${AdminLevels.roman(level)}",
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Escolha da classe: I (mais alta) … X. Explica o que a classe permite. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LevelPicker(level: Int, explain: @Composable (Int) -> String, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..AdminLevels.MAX).forEach { l ->
                FilterChip(selected = l == level, onClick = { onChange(l) }, label = { Text(AdminLevels.roman(l)) })
            }
        }
        Text(explain(level), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LevelDialog(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var level by remember { mutableIntStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.admin_choose_class)) },
        text = {
            LevelPicker(level, explain = { l -> adminClassExplanation(l) }) { level = it }
        },
        confirmButton = { TextButton(onClick = { onConfirm(level) }) { Text(stringResource(R.string.admin_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** "Classe III: usa os comandos de classe III a X. Não usa os de classe I e II." */
@Composable
fun adminClassExplanation(level: Int): String = when (level) {
    1 -> stringResource(R.string.admin_class_explain_top)
    else -> stringResource(
        R.string.admin_class_explain,
        AdminLevels.roman(level),
        AdminLevels.roman(AdminLevels.MAX),
        (1 until level).joinToString(", ") { AdminLevels.roman(it) },
    )
}
