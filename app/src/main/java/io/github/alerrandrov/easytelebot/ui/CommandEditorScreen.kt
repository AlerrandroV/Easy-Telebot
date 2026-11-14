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
import androidx.compose.runtime.Composable
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
private data class Draft(val key: Long, val msg: BotMessage)

/** Botão em edição: lista "achatada" com a marca "na mesma linha do anterior". */
internal data class ButtonDraft(val text: String, val url: String, val sameRow: Boolean)

internal fun toDrafts(rows: List<List<LinkButton>>): List<ButtonDraft> =
    rows.flatMap { row -> row.mapIndexed { i, b -> ButtonDraft(b.text, b.url, i > 0) } }

internal fun toRows(buttons: List<ButtonDraft>): List<List<LinkButton>> {
    val rows = mutableListOf<MutableList<LinkButton>>()
    buttons.forEach { b ->
        val button = LinkButton(b.text.trim(), b.url.trim())
        if (b.sameRow && rows.isNotEmpty() && rows.last().size < 8) rows.last().add(button) else rows.add(mutableListOf(button))
    }
    return rows
}

@Composable
fun CommandEditorScreen(botId: Long, commandId: String?, newAsAdmin: Boolean = false, onDone: () -> Unit) {
    val context = LocalContext.current
    val config = remember { ConfigRepository.load(botId) }
    val original = remember { config.commands.firstOrNull { it.id == commandId } }
    val start = remember { original ?: SimpleCommand(adminLevel = if (newAsAdmin) 1 else null, showInMenu = true) }

    var triggers by remember { mutableStateOf(start.triggers) }
    var triggerInput by remember { mutableStateOf("") }
    var description by remember { mutableStateOf(start.description) }
    var showInMenu by remember { mutableStateOf(start.showInMenu) }
    var adminLevel by remember { mutableStateOf(start.adminLevel) }
    var lastLevel by remember { mutableStateOf(start.adminLevel ?: 1) }
    var nextKey by remember { mutableLongStateOf(start.messages.size.toLong()) }
    var drafts by remember {
        mutableStateOf(start.messages.ifEmpty { listOf(BotMessage()) }.mapIndexed { i, m -> Draft(i.toLong(), m) })
    }
    var showErrors by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val otherTriggers = config.commands.filter { it.id != start.id }.flatMap { it.triggers }.toSet()

    fun current() = start.copy(
        triggers = triggers,
        description = description.trim(),
        showInMenu = showInMenu,
        adminLevel = adminLevel,
        messages = drafts.map { it.msg },
    )
    val changed = current() != start

    val errors = validate(context, triggers, drafts.map { it.msg }, otherTriggers)

    fun addTrigger() {
        val parts = triggerInput.split(' ', ',', ';').map { it.trim().removePrefix("/").lowercase() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        val valid = parts.filter { Variables.COMMAND_NAME.matches(it) }
        triggers = (triggers + valid).distinct()
        triggerInput = if (valid.size == parts.size) "" else parts.filterNot { it in valid }.joinToString(" ")
    }

    fun save() {
        if (triggerInput.isNotBlank()) addTrigger()
        if (validate(context, triggers, drafts.map { it.msg }, otherTriggers).isNotEmpty()) {
            showErrors = true
            return
        }
        val cmd = current().copy(messages = drafts.map { it.msg }.filter { it.text.isNotBlank() || it.media != null })
        val list = config.commands.toMutableList()
        val index = list.indexOfFirst { it.id == cmd.id }
        if (index >= 0) list[index] = cmd else list.add(cmd)
        ConfigRepository.save(botId, config.copy(commands = list))
        Toast.makeText(context, context.getString(R.string.cmd_saved), Toast.LENGTH_SHORT).show()
        onDone()
    }

    BackHandler { if (changed) confirmDiscard = true else onDone() }

    Scaffold(
        topBar = {
            AppBar(
                stringResource(
                    when {
                        original != null -> R.string.cmd_edit
                        newAsAdmin -> R.string.cmd_new_admin
                        else -> R.string.cmd_new
                    }
                ),
                onBack = { if (changed) confirmDiscard = true else onDone() },
            ) {
                if (original != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
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

            item { SectionTitle(stringResource(R.string.cmd_messages_title)) }
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
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        drafts = drafts + Draft(nextKey, BotMessage())
                        nextKey++
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("+  " + stringResource(R.string.cmd_add_message)) }
            }
            item {
                Text(
                    stringResource(R.string.cmd_messages_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    messages: List<BotMessage>,
    otherTriggers: Set<String>,
): List<String> = buildList {
    if (triggers.isEmpty()) add(context.getString(R.string.cmd_err_no_trigger))
    if (AdminLevels.NEWADMIN in triggers) add(context.getString(R.string.cmd_err_reserved, AdminLevels.NEWADMIN))
    triggers.filter { it in otherTriggers }.forEach { add(context.getString(R.string.cmd_err_trigger_taken, it)) }
    if (messages.none { it.text.isNotBlank() || it.media != null }) add(context.getString(R.string.cmd_err_no_message))
    messages.forEachIndexed { i, m ->
        if (m.text.length > TEXT_LIMIT) add(context.getString(R.string.cmd_err_too_long, i + 1))
        if (m.buttons.flatten().any { b -> b.text.isBlank() || Variables.normalizeUrl(b.url) == null }) {
            add(context.getString(R.string.cmd_err_button, i + 1))
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
                            trailingIcon = { Text("✕") },
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
private fun MessageEditor(
    botId: Long,
    index: Int,
    count: Int,
    draft: Draft,
    onChange: (BotMessage) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
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
        val media = MediaImporter.import(context, botId, uri)
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
                TextButton(onClick = { onMove(-1) }, enabled = index > 0) { Text("↑") }
                TextButton(onClick = { onMove(1) }, enabled = index < count - 1) { Text("↓") }
                TextButton(onClick = onRemove, enabled = count > 1) {
                    Text("✕", color = if (count > 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Variables.KNOWN.forEach { v -> AssistChip(onClick = { setText(insert(field, "{$v}")) }, label = { Text("{$v}") }) }
                }
                Text(
                    stringResource(R.string.cmd_variables_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val limit = if (media != null) CAPTION_LIMIT else TEXT_LIMIT
            val unknown = Variables.unknown(field.text)
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

            // Botões (links)
            Text(stringResource(R.string.btn_title), style = MaterialTheme.typography.titleSmall)
            buttons.forEachIndexed { i, b ->
                ButtonEditor(
                    button = b,
                    first = i == 0,
                    onChange = { nb -> setButtons(buttons.mapIndexed { j, x -> if (j == i) nb else x }) },
                    onRemove = { setButtons(buttons.filterIndexed { j, _ -> j != i }) },
                )
            }
            TextButton(onClick = { setButtons(buttons + ButtonDraft("", "", false)) }) {
                Text("+  " + stringResource(R.string.btn_add))
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

@Composable
internal fun ButtonEditor(button: ButtonDraft, first: Boolean, onChange: (ButtonDraft) -> Unit, onRemove: () -> Unit) {
    val urlInvalid = button.url.isNotBlank() && Variables.normalizeUrl(button.url) == null
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = button.text,
                onValueChange = { onChange(button.copy(text = it.take(64))) },
                label = { Text(stringResource(R.string.btn_text)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = button.url,
                onValueChange = { onChange(button.copy(url = it.trim())) },
                label = { Text(stringResource(R.string.btn_url)) },
                placeholder = { Text("https://…  ·  t.me/…  ·  @canal") },
                singleLine = true,
                isError = urlInvalid,
                supportingText = { if (urlInvalid) Text(stringResource(R.string.btn_url_invalid)) },
                modifier = Modifier.fillMaxWidth(),
            )
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
