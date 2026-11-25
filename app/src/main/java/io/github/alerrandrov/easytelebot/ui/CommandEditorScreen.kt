package io.github.alerrandrov.easytelebot.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableIntStateOf
import io.github.alerrandrov.easytelebot.data.CommandBlock
import io.github.alerrandrov.easytelebot.data.WaitCondition
import io.github.alerrandrov.easytelebot.data.Reaction
import io.github.alerrandrov.easytelebot.data.TreeStep
import io.github.alerrandrov.easytelebot.data.BlockTree
import io.github.alerrandrov.easytelebot.data.CommandScript
import io.github.alerrandrov.easytelebot.data.FlowNode
import io.github.alerrandrov.easytelebot.data.ButtonTypes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.AdminLevels
import io.github.alerrandrov.easytelebot.data.BotMessage
import io.github.alerrandrov.easytelebot.data.ConfigRepository
import io.github.alerrandrov.easytelebot.data.LinkButton
import io.github.alerrandrov.easytelebot.data.MediaImporter
import io.github.alerrandrov.easytelebot.data.MessageMedia
import io.github.alerrandrov.easytelebot.data.SimpleCommand
import io.github.alerrandrov.easytelebot.data.Variables

private const val TEXT_LIMIT = 4096
private const val CAPTION_LIMIT = 1024

/** Mensagem em edição, com uma chave estável para a lista. */
internal data class Draft(val key: Long, val msg: BotMessage)

/** Botão em edição: lista "achatada" com a marca "na mesma linha do anterior". */
internal data class ButtonDraft(val text: String, val url: String, val sameRow: Boolean, val type: String = ButtonTypes.URL)

internal fun toDrafts(rows: List<List<LinkButton>>): List<ButtonDraft> =
    rows.flatMap { row -> row.mapIndexed { i, b -> ButtonDraft(b.text, b.url, i > 0, b.type) } }

internal fun toRows(buttons: List<ButtonDraft>): List<List<LinkButton>> {
    val rows = mutableListOf<MutableList<LinkButton>>()
    buttons.forEach { b ->
        val button = LinkButton(b.text.trim(), b.url.trim(), b.type)
        if (b.sameRow && rows.isNotEmpty() && rows.last().size < 8) rows.last().add(button) else rows.add(mutableListOf(button))
    }
    return rows
}

@Composable
fun CommandEditorScreen(
    botId: Long,
    commandId: String?,
    newAsAdmin: Boolean = false,
    newAsScript: Boolean = false,
    /** Etapas de fluxo antigas (comandos que já iniciavam um fluxo). */
    onOpenFlows: () -> Unit = {},
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val config = remember { ConfigRepository.load(botId) }
    val original = remember { config.commands.firstOrNull { it.id == commandId } }
    val start = remember {
        original ?: SimpleCommand(
            adminLevel = if (newAsAdmin) 1 else null,
            showInMenu = true,
            blocks = if (newAsScript) listOf(CommandBlock.Termux(CommandScript())) else emptyList(),
        )
    }

    var triggers by remember { mutableStateOf(start.triggers) }
    var triggerInput by remember { mutableStateOf("") }
    var description by remember { mutableStateOf(start.description) }
    var showInMenu by remember { mutableStateOf(start.showInMenu) }
    var adminLevel by remember { mutableStateOf(start.adminLevel) }
    var lastLevel by remember { mutableStateOf(start.adminLevel ?: 1) }
    var blocks by remember { mutableStateOf(start.blocks) }
    var showErrors by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    val nodeIds = config.flows.map { it.id }.toSet()
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val otherTriggers = config.commands.filter { it.id != start.id }.flatMap { it.triggers }.toSet()

    fun current() = start.copy(
        triggers = triggers,
        description = description.trim(),
        showInMenu = showInMenu,
        adminLevel = adminLevel,
        blocks = blocks,
    )
    val changed = current() != start

    val callbackCounts = (config.commands.filter { it.id != start.id }.flatMap { CommandBlock.callbackValues(it.blocks) } +
        CommandBlock.callbackValues(blocks)).groupingBy { it }.eachCount()
    val errors = validate(context, triggers, blocks, otherTriggers, nodeIds, callbackCounts)

    fun addTrigger() {
        val parts = triggerInput.split(' ', ',', ';').map { it.trim().removePrefix("/").lowercase() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        val valid = parts.filter { Variables.COMMAND_NAME.matches(it) }
        triggers = (triggers + valid).distinct()
        triggerInput = if (valid.size == parts.size) "" else parts.filterNot { it in valid }.joinToString(" ")
    }

    fun save() {
        if (triggerInput.isNotBlank()) addTrigger()
        if (validate(context, triggers, blocks, otherTriggers, nodeIds, callbackCounts).isNotEmpty()) {
            showErrors = true
            return
        }
        val cmd = current()
        val list = config.commands.toMutableList()
        val index = list.indexOfFirst { it.id == cmd.id }
        if (index >= 0) list[index] = cmd else list.add(cmd)
        ConfigRepository.save(botId, config.copy(commands = list))
        Toast.makeText(context, context.getString(R.string.cmd_saved), Toast.LENGTH_SHORT).show()
        onDone()
    }

    /** Pasta aberta: caminho a partir da resposta do comando (vazio = a página do comando). */
    var path by remember { mutableStateOf(listOf<TreeStep>()) }
    val folderBlocks = if (path.isEmpty()) blocks else BlockTree.get(blocks, path)
    LaunchedEffect(folderBlocks == null) { if (folderBlocks == null) path = emptyList() } // pasta apagada
    val env = BlockEditorEnv(
        botId = botId,
        nodes = config.flows,
        extraVariables = config.flowVariables() + config.commands.flatMap { CommandBlock.waitVariables(it.blocks) } +
            CommandBlock.waitVariables(blocks),
        callbackCounts = callbackCounts,
        onOpen = { path = path + it },
        onOpenFlows = if (config.flows.isEmpty()) null else ({
            // Sair do editor descartaria o que não foi salvo.
            if (changed) Toast.makeText(context, context.getString(R.string.block_flow_save_first), Toast.LENGTH_LONG).show()
            else onOpenFlows()
        }),
    )
    val rootName = "/" + (triggers.firstOrNull() ?: triggerInput.trim().removePrefix("/").ifBlank { stringResource(R.string.command_placeholder) })
    val crumbs = folderCrumbs(blocks, path, rootName)

    BackHandler {
        when {
            path.isNotEmpty() -> path = path.dropLast(1)
            changed -> confirmDiscard = true
            else -> onDone()
        }
    }

    Scaffold(
        topBar = {
            AppBar(
                if (path.isNotEmpty()) crumbs.last() else stringResource(
                    when {
                        original != null -> R.string.cmd_edit
                        newAsAdmin -> R.string.cmd_new_admin
                        else -> R.string.cmd_new
                    }
                ),
                onBack = {
                    when {
                        path.isNotEmpty() -> path = path.dropLast(1)
                        changed -> confirmDiscard = true
                        else -> onDone()
                    }
                },
            ) {
                if (original != null && path.isEmpty()) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                IconButton(onClick = { showPreview = true }) {
                    AppIcon(R.drawable.ic_visibility, description = stringResource(R.string.preview_title))
                }
                TextButton(onClick = { save() }) { Text(stringResource(R.string.save)) }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showErrors && errors.isNotEmpty()) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.cmd_err_title), fontWeight = FontWeight.Bold)
                            errors.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }

            if (path.isNotEmpty()) {
                item { FolderPath(crumbs) { depth -> path = path.take(depth) } }
                item {
                    FolderHeader(
                        blocks = blocks,
                        path = path,
                        onChange = { blocks = it },
                    )
                }
                item {
                    ResponseBlocksEditor(env, folderBlocks.orEmpty()) { blocks = BlockTree.set(blocks, path, it) }
                }
                return@LazyColumn
            }

            item {
                TriggersEditor(
                    triggers = triggers,
                    input = triggerInput,
                    taken = otherTriggers,
                    onInput = { triggerInput = it },
                    onAdd = { addTrigger() },
                    onRemove = { t -> triggers = triggers - t },
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it.take(256) },
                            label = { Text(stringResource(R.string.cmd_description)) },
                            supportingText = { Text(stringResource(R.string.cmd_description_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                        )
                        SwitchRow(
                            title = stringResource(R.string.cmd_show_in_menu),
                            subtitle = stringResource(
                                if (adminLevel != null) R.string.cmd_show_in_menu_admin_sub else R.string.cmd_show_in_menu_sub
                            ),
                            checked = showInMenu,
                        ) { showInMenu = it }
                    }
                }
            }
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = if (adminLevel != null) {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    } else {
                        CardDefaults.cardColors()
                    },
                ) {
                    Column {
                        SwitchRow(
                            title = stringResource(R.string.cmd_admin_only),
                            subtitle = stringResource(R.string.cmd_admin_only_sub),
                            checked = adminLevel != null,
                        ) { on -> adminLevel = if (on) lastLevel else null }
                        val level = adminLevel
                        if (level != null) {
                            Column(
                                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(stringResource(R.string.cmd_admin_level), style = MaterialTheme.typography.titleSmall)
                                LevelPicker(level, explain = { l -> commandLevelExplanation(l) }) {
                                    adminLevel = it
                                    lastLevel = it
                                }
                            }
                        }
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.cmd_response_title)) }
            item {
                ResponseBlocksEditor(env, blocks) { blocks = it }
            }
        }
    }

    if (showPreview) {
        CommandPreviewDialog(triggers.firstOrNull() ?: triggerInput.trim().removePrefix("/"), blocks, botId, config.flows) {
            showPreview = false
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.cmd_discard_title)) },
            text = { Text(stringResource(R.string.cmd_discard_msg)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; save() }) { Text(stringResource(R.string.save)) } },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false; onDone() }) { Text(stringResource(R.string.discard)) }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.cmd_delete_title)) },
            text = { Text(stringResource(R.string.cmd_delete_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    ConfigRepository.save(botId, config.copy(commands = config.commands.filter { it.id != start.id }))
                    onDone()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun validate(
    context: android.content.Context,
    triggers: List<String>,
    blocks: List<CommandBlock>,
    otherTriggers: Set<String>,
    nodeIds: Set<String>,
    callbackCounts: Map<String, Int>,
): List<String> = buildList {
    if (triggers.isEmpty()) add(context.getString(R.string.cmd_err_no_trigger))
    triggers.filter { it in AdminLevels.RESERVED }.forEach { add(context.getString(R.string.cmd_err_reserved, it)) }
    triggers.filter { it in otherTriggers }.forEach { add(context.getString(R.string.cmd_err_trigger_taken, it)) }
    if (blocks.isEmpty()) add(context.getString(R.string.cmd_err_no_blocks))
    validateBlocks(context, blocks, null, nodeIds, this)
    callbackCounts.filter { it.value > 1 && it.key.isNotBlank() }.keys.forEach { add(context.getString(R.string.cmd_err_callback_dup, it)) }
}

/** Confere uma lista de blocos e as pastas dentro dela. `where` = nome da pasta (null = o comando). */
private fun validateBlocks(
    context: android.content.Context,
    blocks: List<CommandBlock>,
    where: String?,
    nodeIds: Set<String>,
    out: MutableList<String>,
) {
    fun add(msg: String) { out += if (where == null) msg else context.getString(R.string.cmd_err_in_folder, where, msg) }
    blocks.forEachIndexed { i, b ->
        val n = i + 1
        when (b) {
            is CommandBlock.Message -> {
                val m = b.msg
                if (m.text.isBlank() && m.media == null) add(context.getString(R.string.cmd_err_block_empty, n))
                if (m.text.length > (if (m.media != null) CAPTION_LIMIT else TEXT_LIMIT)) add(context.getString(R.string.cmd_err_too_long, n))
                if (m.buttons.flatten().any { !ButtonTypes.isValid(it, nodeIds) }) add(context.getString(R.string.cmd_err_button, n))
                m.buttons.flatten().filter { it.type == ButtonTypes.CALLBACK }.forEach { btn ->
                    validateBlocks(context, btn.reaction?.blocks.orEmpty(), btn.text.ifBlank { btn.url }, nodeIds, out)
                }
            }
            is CommandBlock.Termux -> if (b.script.command.isBlank()) add(context.getString(R.string.cmd_err_block_script, n))
            is CommandBlock.Flow -> if (b.node !in nodeIds) add(context.getString(R.string.cmd_err_no_flow))
            is CommandBlock.Code -> if (b.code.isBlank()) add(context.getString(R.string.cmd_err_block_code, n))
            is CommandBlock.Wait -> if (b.usesCode) Unit else {
                b.rules.forEachIndexed { j, r ->
                    if (r.condition.field == "text" && r.condition.op !in WaitCondition.NO_VALUE && r.condition.value.isBlank()) {
                        add(context.getString(R.string.cmd_err_rule_value, j + 1))
                    }
                    validateBlocks(context, r.blocks, context.getString(R.string.rule_n, j + 1), nodeIds, out)
                }
                validateBlocks(context, b.otherwise, context.getString(R.string.rule_otherwise), nodeIds, out)
            }
        }
    }
}

/** "Admins da classe I até a III podem usar." */
@Composable
private fun commandLevelExplanation(level: Int): String =
    if (level == 1) {
        stringResource(R.string.cmd_admin_level_explain_top)
    } else {
        stringResource(
            R.string.cmd_admin_level_explain,
            (1..level).joinToString(", ") { AdminLevels.roman(it) },
            (level + 1..AdminLevels.MAX).joinToString(", ") { AdminLevels.roman(it) }.ifEmpty { "—" },
        )
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriggersEditor(
    triggers: List<String>,
    input: String,
    taken: Set<String>,
    onInput: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val clean = input.trim().removePrefix("/").lowercase()
    val invalid = clean.isNotEmpty() && clean.split(' ', ',', ';').any {
        it.isNotEmpty() && !Variables.COMMAND_NAME.matches(it.removePrefix("/"))
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cmd_triggers), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.cmd_triggers_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (triggers.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    triggers.forEach { t ->
                        InputChip(
                            selected = t in taken,
                            onClick = { onRemove(t) },
                            label = { Text("/$t") },
                            trailingIcon = { AppIcon(R.drawable.ic_close, Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                label = { Text(stringResource(R.string.cmd_name)) },
                prefix = { Text("/") },
                singleLine = true,
                isError = invalid,
                supportingText = { Text(stringResource(if (invalid) R.string.cmd_name_invalid else R.string.cmd_name_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAdd() }),
                trailingIcon = { TextButton(onClick = onAdd, enabled = clean.isNotEmpty()) { Text(stringResource(R.string.add)) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Envolve a seleção com marcadores (ou insere e põe o cursor no meio). */
internal fun wrap(v: TextFieldValue, prefix: String, suffix: String): TextFieldValue {
    val start = minOf(v.selection.start, v.selection.end).coerceIn(0, v.text.length)
    val end = maxOf(v.selection.start, v.selection.end).coerceIn(0, v.text.length)
    val selected = v.text.substring(start, end)
    val text = v.text.substring(0, start) + prefix + selected + suffix + v.text.substring(end)
    val cursor = if (selected.isEmpty()) TextRange(start + prefix.length) else TextRange(start + prefix.length + selected.length + suffix.length)
    return TextFieldValue(text, cursor)
}

internal fun insert(v: TextFieldValue, token: String): TextFieldValue = wrap(v, token, "")

/** Põe "> " no começo das linhas selecionadas (citação). */
internal fun quote(v: TextFieldValue): TextFieldValue {
    val start = minOf(v.selection.start, v.selection.end).coerceIn(0, v.text.length)
    val lineStart = v.text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (start == 0 || it < 0) 0 else it + 1 }
    val text = v.text.substring(0, lineStart) + "> " + v.text.substring(lineStart)
    return TextFieldValue(text, TextRange(v.selection.end + 2))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MessageEditor(
    botId: Long,
    index: Int,
    count: Int,
    draft: Draft,
    onChange: (BotMessage) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    /** "media" para comandos (limpa ao salvar); "outbox" para envios avulsos. */
    mediaFolder: String = "media",
    /** Tipos de botão oferecidos (o tipo "etapa" só aparece se houver [nodes]). */
    buttonTypes: List<String> = ButtonTypes.ALL,
    /** Etapas de fluxo para os botões "ir para etapa". */
    nodes: List<FlowNode> = emptyList(),
    /** Variáveis extras aceitas no texto (as dos fluxos, como {cidade}). */
    extraVariables: Set<String> = emptySet(),
) {
    val context = LocalContext.current
    val msg = draft.msg
    var field by remember(draft.key) { mutableStateOf(TextFieldValue(msg.text)) }
    var buttons by remember(draft.key) { mutableStateOf(toDrafts(msg.buttons)) }
    var showVariables by remember(draft.key) { mutableStateOf(false) }

    fun setText(v: TextFieldValue) {
        field = v
        onChange(msg.copy(text = v.text))
    }

    fun setButtons(list: List<ButtonDraft>) {
        buttons = list
        onChange(msg.copy(text = field.text, buttons = toRows(list)))
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val media = MediaImporter.import(context, botId, uri, mediaFolder)
        if (media == null) {
            Toast.makeText(context, context.getString(R.string.media_import_failed), Toast.LENGTH_LONG).show()
        } else {
            onChange(msg.copy(text = field.text, media = media))
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.cmd_message_n, index + 1),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (count > 1) {
                    IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                        AppIcon(R.drawable.ic_arrow_upward, description = stringResource(R.string.cmd_move_up))
                    }
                    IconButton(onClick = { onMove(1) }, enabled = index < count - 1) {
                        AppIcon(R.drawable.ic_arrow_downward, description = stringResource(R.string.cmd_move_down))
                    }
                    IconButton(onClick = onRemove) {
                        AppIcon(R.drawable.ic_delete, tint = MaterialTheme.colorScheme.error, description = stringResource(R.string.delete))
                    }
                }
            }

            // Mídia
            val media = msg.media
            if (media == null) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.media_attach))
                }
            } else {
                MediaRow(
                    media = media,
                    onAsDocument = { asDoc ->
                        val ext = media.name.substringAfterLast('.', "").lowercase()
                        val type = if (asDoc) "document" else MediaImporter.typeFor(guessMime(ext), ext)
                        onChange(msg.copy(text = field.text, media = media.copy(type = type)))
                    },
                    onRemove = { onChange(msg.copy(text = field.text, media = null)) },
                )
            }

            // Barra de formatação
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                FormatButton("B", fontWeight = FontWeight.Bold) { setText(wrap(field, "*", "*")) }
                FormatButton("I", fontStyle = FontStyle.Italic) { setText(wrap(field, "_", "_")) }
                FormatButton("U", decoration = TextDecoration.Underline) { setText(wrap(field, "__", "__")) }
                FormatButton("S", decoration = TextDecoration.LineThrough) { setText(wrap(field, "~", "~")) }
                FormatButton("</>", mono = true) { setText(wrap(field, "`", "`")) }
                FormatButton(stringResource(R.string.fmt_spoiler)) { setText(wrap(field, "||", "||")) }
                FormatButton(stringResource(R.string.fmt_link)) {
                    val v = wrap(field, "[", "](https://)")
                    setText(v.copy(selection = TextRange(v.selection.start - 1)))
                }
                FormatButton(stringResource(R.string.fmt_quote)) { setText(quote(field)) }
                FormatButton("{ }") { showVariables = !showVariables }
            }

            if (showVariables) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Variables.shown(LocalContext.current).forEach { v -> AssistChip(onClick = { setText(insert(field, "{$v}")) }, label = { Text("{$v}") }) }
                }
                Text(
                    stringResource(R.string.cmd_variables_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val limit = if (media != null) CAPTION_LIMIT else TEXT_LIMIT
            val unknown = Variables.unknown(field.text).filter { it !in extraVariables }
            OutlinedTextField(
                value = field,
                onValueChange = { setText(it) },
                label = { Text(stringResource(if (media != null) R.string.cmd_caption else R.string.cmd_text)) },
                minLines = 4,
                isError = unknown.isNotEmpty() || field.text.length > TEXT_LIMIT,
                supportingText = {
                    Column {
                        if (unknown.isNotEmpty()) {
                            Text(stringResource(R.string.cmd_unknown_vars, unknown.joinToString { "{$it}" }))
                        }
                        if (media != null && field.text.length > CAPTION_LIMIT) {
                            Text(stringResource(R.string.cmd_caption_too_long))
                        }
                        Text("${field.text.length}/$limit")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // Botões
            Text(stringResource(R.string.btn_title), style = MaterialTheme.typography.titleSmall)
            buttons.forEachIndexed { i, b ->
                ButtonEditor(
                    button = b,
                    first = i == 0,
                    onChange = { nb -> setButtons(buttons.mapIndexed { j, x -> if (j == i) nb else x }) },
                    onRemove = { setButtons(buttons.filterIndexed { j, _ -> j != i }) },
                    types = buttonTypes.filter { it != ButtonTypes.NODE || nodes.isNotEmpty() || b.type == ButtonTypes.NODE },
                    nodes = nodes,
                )
            }
            TextButton(onClick = { setButtons(buttons + ButtonDraft("", "", false)) }) {
                ButtonLabel(R.drawable.ic_add, stringResource(R.string.btn_add))
            }

            if (media == null) {
                Row(
                    Modifier.fillMaxWidth().clickable { onChange(msg.copy(text = field.text, linkPreview = !msg.linkPreview)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = msg.linkPreview, onCheckedChange = { onChange(msg.copy(text = field.text, linkPreview = it)) })
                    Text(stringResource(R.string.cmd_link_preview), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun guessMime(ext: String): String =
    android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: ""

@Composable
private fun MediaRow(media: MessageMedia, onAsDocument: (Boolean) -> Unit, onRemove: () -> Unit) {
    val label = when (media.type) {
        "photo" -> R.string.media_photo
        "video" -> R.string.media_video
        "animation" -> R.string.media_animation
        "audio" -> R.string.media_audio
        "voice" -> R.string.media_voice
        else -> R.string.media_document
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                    Text(media.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.remove)) }
            }
            Row(
                Modifier.clickable { onAsDocument(media.type != "document") },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = media.type == "document", onCheckedChange = { onAsDocument(it) })
                Text(stringResource(R.string.media_as_document), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ButtonEditor(
    button: ButtonDraft,
    first: Boolean,
    onChange: (ButtonDraft) -> Unit,
    onRemove: () -> Unit,
    types: List<String> = listOf(ButtonTypes.URL),
    nodes: List<FlowNode> = emptyList(),
) {
    val asButton = LinkButton(button.text, button.url, button.type)
    val invalid = button.url.isNotBlank() && !ButtonTypes.isValid(asButton.copy(text = "x"), nodes.map { it.id }.toSet())
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (types.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    types.forEach { t ->
                        FilterChip(
                            selected = button.type == t,
                            onClick = { if (button.type != t) onChange(button.copy(type = t, url = "")) },
                            label = { Text(buttonTypeLabel(t)) },
                            leadingIcon = { AppIcon(buttonTypeIcon(t), Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = button.text,
                onValueChange = { onChange(button.copy(text = it.take(64))) },
                label = { Text(stringResource(R.string.btn_text)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (button.type == ButtonTypes.NODE) {
                Text(stringResource(R.string.btn_node_pick), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    nodes.forEach { n ->
                        FilterChip(
                            selected = button.url == n.id,
                            onClick = { onChange(button.copy(url = n.id)) },
                            label = { Text(n.title.ifBlank { n.id }) },
                        )
                    }
                }
                if (button.url.isNotBlank() && nodes.none { it.id == button.url }) {
                    Text(stringResource(R.string.btn_node_missing), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                OutlinedTextField(
                    value = button.url,
                    onValueChange = { onChange(button.copy(url = if (button.type == ButtonTypes.COPY) it.take(256) else it.trim())) },
                    label = { Text(buttonValueLabel(button.type)) },
                    placeholder = { Text(buttonValueHint(button.type)) },
                    singleLine = button.type != ButtonTypes.COPY,
                    isError = invalid,
                    supportingText = { if (invalid) Text(buttonValueError(button.type)) else Text(buttonValueHelp(button.type)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!first) {
                    Checkbox(checked = button.sameRow, onCheckedChange = { onChange(button.copy(sameRow = it)) })
                    Text(stringResource(R.string.btn_same_row), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                } else {
                    Text("", modifier = Modifier.weight(1f))
                }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
internal fun buttonTypeLabel(type: String): String = stringResource(
    when (type) {
        ButtonTypes.COPY -> R.string.btn_type_copy
        ButtonTypes.USER -> R.string.btn_type_user
        ButtonTypes.CHANNEL -> R.string.btn_type_channel
        ButtonTypes.GROUP -> R.string.btn_type_group
        ButtonTypes.NODE -> R.string.btn_type_node
        ButtonTypes.WEBAPP -> R.string.btn_type_webapp
        ButtonTypes.CALLBACK -> R.string.btn_type_callback
        else -> R.string.btn_type_url
    },
)

internal fun buttonTypeIcon(type: String): Int = when (type) {
    ButtonTypes.COPY -> R.drawable.ic_content_copy
    ButtonTypes.USER -> R.drawable.ic_person
    ButtonTypes.CHANNEL -> R.drawable.ic_campaign
    ButtonTypes.GROUP -> R.drawable.ic_group
    ButtonTypes.NODE -> R.drawable.ic_account_tree
    ButtonTypes.WEBAPP -> R.drawable.ic_web_asset
    ButtonTypes.CALLBACK -> R.drawable.ic_terminal
    else -> R.drawable.ic_link
}

@Composable
internal fun buttonValueLabel(type: String): String = stringResource(
    when (type) {
        ButtonTypes.USER -> R.string.btn_value_user
        ButtonTypes.CHANNEL -> R.string.btn_value_channel
        ButtonTypes.GROUP -> R.string.btn_value_group
        ButtonTypes.COPY -> R.string.btn_value_copy
        ButtonTypes.WEBAPP -> R.string.btn_value_webapp
        ButtonTypes.CALLBACK -> R.string.btn_value_callback
        else -> R.string.btn_url
    },
)

@Composable
internal fun buttonValueHint(type: String): String = when (type) {
    ButtonTypes.USER -> stringResource(R.string.btn_hint_user)
    ButtonTypes.CHANNEL -> stringResource(R.string.btn_hint_channel)
    ButtonTypes.GROUP -> stringResource(R.string.btn_hint_group)
    ButtonTypes.COPY -> stringResource(R.string.btn_hint_copy)
    ButtonTypes.WEBAPP -> "https://…"
    ButtonTypes.CALLBACK -> "pedido:123"
    else -> "https://…  ·  t.me/…  ·  @canal"
}

@Composable
internal fun buttonValueHelp(type: String): String = when (type) {
    ButtonTypes.COPY -> stringResource(R.string.btn_help_copy)
    ButtonTypes.WEBAPP -> stringResource(R.string.btn_help_webapp)
    ButtonTypes.CALLBACK -> stringResource(R.string.btn_help_callback)
    else -> ""
}

@Composable
internal fun buttonValueError(type: String): String = stringResource(
    when (type) {
        ButtonTypes.WEBAPP -> R.string.btn_err_webapp
        ButtonTypes.CALLBACK -> R.string.btn_err_callback
        ButtonTypes.COPY -> R.string.btn_err_copy
        else -> R.string.btn_url_invalid
    },
)

@Composable
internal fun FormatButton(
    label: String,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    decoration: TextDecoration? = null,
    mono: Boolean = false,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
        Text(
            label,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            textDecoration = decoration,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}

/** Nomes do caminho até a pasta aberta: /start › Planos › Premium. */
@Composable
private fun folderCrumbs(blocks: List<CommandBlock>, path: List<TreeStep>, root: String): List<String> {
    val names = mutableListOf(root)
    var current: List<CommandBlock> = blocks
    path.forEach { step ->
        names += when (step) {
            is TreeStep.Button -> BlockTree.button(current, step)?.text?.ifBlank { null } ?: stringResource(R.string.block_button_untitled)
            is TreeStep.Rule -> stringResource(R.string.rule_n, step.rule + 1)
            is TreeStep.Otherwise -> stringResource(R.string.rule_otherwise)
        }
        current = BlockTree.get(current, listOf(step)) ?: emptyList()
    }
    return names
}

/** Caminho clicável, como num gerenciador de arquivos. */
@Composable
private fun FolderPath(crumbs: List<String>, onJump: (Int) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { i, name ->
            item {
                if (i > 0) AppIcon(R.drawable.ic_chevron_right, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                val last = i == crumbs.lastIndex
                TextButton(onClick = { onJump(i) }, enabled = !last, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    if (i == 0) {
                        AppIcon(R.drawable.ic_chat, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                    } else {
                        AppIcon(R.drawable.ic_folder_open, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(name, maxLines = 1, fontWeight = if (last) FontWeight.Bold else null)
                }
            }
        }
    }
}

/** O que abre esta pasta (botão ou regra) e as opções dela. */
@Composable
private fun FolderHeader(blocks: List<CommandBlock>, path: List<TreeStep>, onChange: (List<CommandBlock>) -> Unit) {
    val parentPath = path.dropLast(1)
    val parent = BlockTree.get(blocks, parentPath) ?: return
    val step = path.last()
    fun setParent(list: List<CommandBlock>) = onChange(BlockTree.set(blocks, parentPath, list))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            when (step) {
                is TreeStep.Button -> {
                    val btn = BlockTree.button(parent, step) ?: return@Column
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconLabel(R.drawable.ic_smart_button, stringResource(R.string.folder_button_when, btn.text.ifBlank { "?" }),
                            style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.folder_button_callback, btn.url.ifBlank { "?" }),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    SwitchRow(
                        stringResource(R.string.folder_edit),
                        stringResource(R.string.folder_edit_sub),
                        btn.reaction?.edit ?: true,
                    ) { v -> setParent(BlockTree.updateButton(parent, step) { it.copy(reaction = (it.reaction ?: Reaction()).copy(edit = v)) }) }
                }
                is TreeStep.Rule -> {
                    val wait = BlockTree.wait(parent, step.block) ?: return@Column
                    val rule = wait.rules.getOrNull(step.rule) ?: return@Column
                    Text(stringResource(R.string.folder_rule_when, step.rule + 1), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchRow(stringResource(R.string.rule_keep), stringResource(R.string.rule_keep_sub), rule.keep) { v ->
                        setParent(BlockTree.updateWait(parent, step.block) { w ->
                            w.copy(rules = w.rules.mapIndexed { i, r -> if (i == step.rule) r.copy(keep = v) else r })
                        })
                    }
                }
                is TreeStep.Otherwise -> {
                    val wait = BlockTree.wait(parent, step.block) ?: return@Column
                    Text(stringResource(R.string.folder_otherwise), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchRow(stringResource(R.string.rule_keep), stringResource(R.string.otherwise_keep_sub), wait.otherwiseKeep) { v ->
                        setParent(BlockTree.updateWait(parent, step.block) { it.copy(otherwiseKeep = v) })
                    }
                }
            }
        }
    }
}
