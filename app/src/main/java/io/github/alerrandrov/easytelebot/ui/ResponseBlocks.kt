package io.github.alerrandrov.easytelebot.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.alerrandrov.easytelebot.R
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import io.github.alerrandrov.easytelebot.data.BotMessage
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.ButtonTypes
import io.github.alerrandrov.easytelebot.data.CommandBlock
import io.github.alerrandrov.easytelebot.data.CommandScript
import io.github.alerrandrov.easytelebot.data.FlowNode
import io.github.alerrandrov.easytelebot.data.LinkButton
import io.github.alerrandrov.easytelebot.data.MediaImporter
import io.github.alerrandrov.easytelebot.data.Variables
import io.github.alerrandrov.easytelebot.data.WaitRule
import io.github.alerrandrov.easytelebot.data.WaitCondition
import io.github.alerrandrov.easytelebot.data.TreeStep
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TEXT_MAX = 4096
private const val CAPTION_MAX = 1024

// -- Lista de blocos ---------------------------------------------------------------------------

/** O que os cards de bloco precisam saber do comando inteiro. */
class BlockEditorEnv(
    val botId: Long,
    val nodes: List<FlowNode>,
    val extraVariables: Set<String>,
    /** Quantas vezes cada texto de callback aparece no bot (precisa ser 1). */
    val callbackCounts: Map<String, Int>,
    /** Abre a "pasta" de um botão callback ou de uma regra do Aguardar resposta. */
    val onOpen: (TreeStep) -> Unit,
    /** Etapas de fluxo antigas (só para comandos que já iniciavam um fluxo). */
    val onOpenFlows: (() -> Unit)? = null,
)

/**
 * Resposta em blocos (de um comando ou de uma pasta). Começa vazia, com o botão Adicionar
 * (Mensagem simples, Termux, Aguardar resposta); cada bloco é um card compacto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResponseBlocksEditor(env: BlockEditorEnv, blocks: List<CommandBlock>, onChange: (List<CommandBlock>) -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    val botId = env.botId
    val nodes = env.nodes
    val extraVariables = env.extraVariables
    val hasWait = blocks.any { it is CommandBlock.Wait }

    /** Novos blocos entram antes do Aguardar resposta (ele sempre fecha a lista). */
    fun add(b: CommandBlock) {
        val w = blocks.indexOfFirst { it is CommandBlock.Wait }
        onChange(if (w < 0 || b is CommandBlock.Wait) blocks + b else blocks.toMutableList().apply { add(w, b) })
    }

    fun replace(i: Int, b: CommandBlock) = onChange(blocks.mapIndexed { j, x -> if (j == i) b else x })
    fun move(i: Int, delta: Int) {
        val t = i + delta
        if (t !in blocks.indices) return
        onChange(blocks.toMutableList().apply { add(t, removeAt(i)) })
    }
    fun duplicate(i: Int) = onChange(blocks.toMutableList().apply {
        add(i + 1, when (val b = blocks[i]) {
            is CommandBlock.Message -> b.copy(key = CommandBlock.newKey())
            is CommandBlock.Termux -> b.copy(key = CommandBlock.newKey())
            is CommandBlock.Flow -> b.copy(key = CommandBlock.newKey())
            is CommandBlock.Code -> b.copy(key = CommandBlock.newKey())
            is CommandBlock.Http -> b.copy(key = CommandBlock.newKey())
            is CommandBlock.Form -> b.copy(key = CommandBlock.newKey(), id = java.util.UUID.randomUUID().toString().take(8))
            is CommandBlock.Wait -> b.copy(key = CommandBlock.newKey())
        })
    })
    fun remove(i: Int) = onChange(blocks.filterIndexed { j, _ -> j != i })

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (blocks.isEmpty()) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AppIcon(R.drawable.ic_chat, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.blocks_empty), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Button(onClick = { showAdd = true }) { ButtonLabel(R.drawable.ic_add, stringResource(R.string.blocks_add)) }
                }
            }
        }
        blocks.forEachIndexed { i, block ->
            val isWait = block is CommandBlock.Wait
            val actions = BlockActions(
                canUp = i > 0 && !isWait, canDown = i < blocks.lastIndex && blocks[i + 1] !is CommandBlock.Wait && !isWait,
                onUp = { move(i, -1) }, onDown = { move(i, 1) },
                onDuplicate = if (isWait) null else ({ duplicate(i) }), onDelete = { remove(i) },
            )
            when (block) {
                is CommandBlock.Message -> MessageBlockCard(env, block.key, block.msg, { replace(i, block.copy(msg = it)) }, actions)
                is CommandBlock.Termux -> TermuxBlockCard(block.script, { replace(i, block.copy(script = it)) }, actions)
                is CommandBlock.Flow -> FlowBlockCard(block.node, nodes, { replace(i, block.copy(node = it)) }, actions, env.onOpenFlows)
                is CommandBlock.Wait -> WaitBlockCard(env, block, { replace(i, it) }, actions)
                is CommandBlock.Code -> CodeBlockCard(block.code, { replace(i, block.copy(code = it)) }, actions)
                is CommandBlock.Http -> HttpBlockCard(block, { replace(i, it) }, actions)
                is CommandBlock.Form -> FormBlockCard(env.botId, block, { replace(i, it) }, actions)
            }
        }
        if (blocks.isNotEmpty()) {
            OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                ButtonLabel(R.drawable.ic_add, stringResource(R.string.blocks_add))
            }
        }
    }

    if (showAdd) {
        ModalBottomSheet(onDismissRequest = { showAdd = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.blocks_add_title), style = MaterialTheme.typography.titleLarge)
                SectionCard(
                    icon = R.drawable.ic_chat,
                    title = stringResource(R.string.block_message),
                    subtitle = stringResource(R.string.block_message_sub),
                    onClick = { add(CommandBlock.Message(BotMessage())); showAdd = false },
                )
                SectionCard(
                    icon = R.drawable.ic_terminal,
                    title = stringResource(R.string.block_termux),
                    subtitle = stringResource(R.string.block_termux_sub),
                    onClick = { add(CommandBlock.Termux(CommandScript())); showAdd = false },
                )
                val sample = stringResource(R.string.code_sample)
                SectionCard(
                    icon = R.drawable.ic_code,
                    title = stringResource(R.string.block_code),
                    subtitle = stringResource(R.string.block_code_sub),
                    onClick = { add(CommandBlock.Code(sample)); showAdd = false },
                )
                SectionCard(
                    icon = R.drawable.ic_list_alt,
                    title = stringResource(R.string.block_form),
                    subtitle = stringResource(R.string.block_form_sub),
                    onClick = { add(CommandBlock.Form()); showAdd = false },
                )
                SectionCard(
                    icon = R.drawable.ic_public,
                    title = stringResource(R.string.block_http),
                    subtitle = stringResource(R.string.block_http_sub),
                    onClick = { add(CommandBlock.Http()); showAdd = false },
                )
                if (!hasWait) SectionCard(
                    icon = R.drawable.ic_hearing,
                    title = stringResource(R.string.block_wait),
                    subtitle = stringResource(R.string.block_wait_sub),
                    onClick = { add(CommandBlock.Wait()); showAdd = false },
                )
            }
        }
    }
}

/** Ações do menu de três pontinhos de cada bloco. */
private class BlockActions(
    val canUp: Boolean,
    val canDown: Boolean,
    val onUp: () -> Unit,
    val onDown: () -> Unit,
    val onDuplicate: (() -> Unit)?,
    val onDelete: () -> Unit,
)

@Composable
private fun BlockHeader(icon: Int, title: String, actions: BlockActions, extra: @Composable (dismiss: () -> Unit) -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                AppIcon(R.drawable.ic_more_vert, Modifier.size(20.dp), description = stringResource(R.string.more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                extra { menu = false }
                if (actions.canUp) DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_move_up)) },
                    leadingIcon = { AppIcon(R.drawable.ic_arrow_upward) },
                    onClick = { menu = false; actions.onUp() },
                )
                if (actions.canDown) DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_move_down)) },
                    leadingIcon = { AppIcon(R.drawable.ic_arrow_downward) },
                    onClick = { menu = false; actions.onDown() },
                )
                actions.onDuplicate?.let { dup ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.block_duplicate)) },
                        leadingIcon = { AppIcon(R.drawable.ic_content_copy) },
                        onClick = { menu = false; dup() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { AppIcon(R.drawable.ic_delete, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; actions.onDelete() },
                )
            }
        }
    }
}

// -- Bloco: mensagem -----------------------------------------------------------------------------

@Composable
private fun MessageBlockCard(
    env: BlockEditorEnv,
    key: String,
    msg: BotMessage,
    onChange: (BotMessage) -> Unit,
    actions: BlockActions,
) {
    val botId = env.botId
    val nodes = env.nodes
    val extraVariables = env.extraVariables
    val context = LocalContext.current
    var field by remember(key) { mutableStateOf(TextFieldValue(msg.text)) }
    var editing by remember(key) { mutableStateOf<Pair<Int, Int>?>(null) }
    var formatMenu by remember { mutableStateOf(false) }
    var varsMenu by remember { mutableStateOf(false) }
    var keyboardSheet by remember { mutableStateOf(false) }

    fun setText(v: TextFieldValue) {
        field = v
        onChange(msg.copy(text = v.text))
    }

    fun pick(forceType: String?) = { uri: android.net.Uri? ->
        if (uri != null) {
            val m = MediaImporter.import(context, botId, uri)
            if (m == null) Toast.makeText(context, context.getString(R.string.media_import_failed), Toast.LENGTH_LONG).show()
            else onChange(msg.copy(text = field.text, media = if (forceType != null) m.copy(type = forceType) else m))
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), pick(null))
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), pick(null))
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), pick(null))
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), pick("document"))

    val media = msg.media
    val limit = if (media != null) CAPTION_MAX else TEXT_MAX
    val unknown = Variables.unknown(field.text).filter { it !in extraVariables }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BlockHeader(R.drawable.ic_chat, stringResource(R.string.block_message), actions) { dismiss ->
                if (media == null) DropdownMenuItem(
                    text = { Text(stringResource(if (msg.linkPreview) R.string.block_preview_off else R.string.block_preview_on)) },
                    leadingIcon = { AppIcon(R.drawable.ic_link) },
                    onClick = { dismiss(); onChange(msg.copy(text = field.text, linkPreview = !msg.linkPreview)) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reply_kb_title)) },
                    leadingIcon = { AppIcon(R.drawable.ic_keyboard) },
                    onClick = { dismiss(); keyboardSheet = true },
                )
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (media != null) MediaChip(botId, media.type, media.file, media.name) { onChange(msg.copy(text = field.text, media = null)) }

                TextField(
                    value = field,
                    onValueChange = { if (it.text.length <= limit) setText(it) },
                    placeholder = { Text(stringResource(if (media != null) R.string.block_caption_hint else R.string.block_text_hint)) },
                    minLines = 2,
                    shape = RoundedCornerShape(12.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (unknown.isNotEmpty()) {
                    Text(stringResource(R.string.cmd_unknown_vars, unknown.joinToString { "{$it}" }),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                // Barra de recursos: mídias, botão, formatação, variáveis.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ToolIcon(R.drawable.ic_image, R.string.block_add_image) { pickImage.launch(arrayOf("image/*")) }
                    ToolIcon(R.drawable.ic_movie, R.string.block_add_video) { pickVideo.launch(arrayOf("video/*")) }
                    ToolIcon(R.drawable.ic_music_note, R.string.block_add_audio) { pickAudio.launch(arrayOf("audio/*")) }
                    ToolIcon(R.drawable.ic_description, R.string.block_add_file) { pickFile.launch(arrayOf("*/*")) }
                    ToolIcon(R.drawable.ic_smart_button, R.string.block_add_button) {
                        val (rows, pos) = GridOps.addRow(msg.buttons)
                        onChange(msg.copy(text = field.text, buttons = rows))
                        editing = pos
                    }
                    Box {
                        ToolIcon(R.drawable.ic_edit, R.string.chat_formatting) { formatMenu = true }
                        DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                            listOf(
                                R.string.fmt_bold to { v: TextFieldValue -> wrap(v, "*", "*") },
                                R.string.fmt_italic to { v: TextFieldValue -> wrap(v, "_", "_") },
                                R.string.fmt_underline to { v: TextFieldValue -> wrap(v, "__", "__") },
                                R.string.fmt_strike to { v: TextFieldValue -> wrap(v, "~", "~") },
                                R.string.fmt_spoiler to { v: TextFieldValue -> wrap(v, "||", "||") },
                                R.string.fmt_code to { v: TextFieldValue -> wrap(v, "`", "`") },
                                R.string.fmt_link to { v: TextFieldValue ->
                                    val w = wrap(v, "[", "](https://)")
                                    w.copy(selection = androidx.compose.ui.text.TextRange(w.selection.start - 1))
                                },
                                R.string.fmt_quote to { v: TextFieldValue -> quote(v) },
                            ).forEach { (label, format) ->
                                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { formatMenu = false; setText(format(field)) })
                            }
                        }
                    }
                    Box {
                        TextButton(onClick = { varsMenu = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text("{ }", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        DropdownMenu(expanded = varsMenu, onDismissRequest = { varsMenu = false }) {
                            (Variables.shown(LocalContext.current) + extraVariables).forEach { v ->
                                DropdownMenuItem(
                                    text = { Text("{$v}", fontFamily = FontFamily.Monospace) },
                                    onClick = { varsMenu = false; setText(insert(field, "{$v}")) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("${field.text.length}/$limit", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (msg.buttons.isNotEmpty()) {
                    ButtonGrid(
                        msg.buttons, nodes.map { it.id }.toSet(), env.callbackCounts,
                        onOpen = { r, c -> env.onOpen(TreeStep.Button(key, r, c)) },
                    ) { r, c -> editing = r to c }
                }
                msg.replyKeyboard?.let { kb ->
                    if (msg.buttons.isNotEmpty()) Text(stringResource(R.string.reply_kb_conflict), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    ReplyKeyboardPreview(kb) { keyboardSheet = true }
                }
            }
        }
    }

    if (keyboardSheet) {
        ReplyKeyboardSheet(msg.replyKeyboard, { kb -> onChange(msg.copy(text = field.text, replyKeyboard = kb)) }) { keyboardSheet = false }
    }

    editing?.let { (r, c) ->
        val button = msg.buttons.getOrNull(r)?.getOrNull(c)
        if (button == null) {
            editing = null
        } else {
            ButtonSheet(
                button = button,
                rowSize = msg.buttons[r].size,
                nodes = nodes,
                duplicateCallback = button.type == ButtonTypes.CALLBACK && (env.callbackCounts[button.url] ?: 0) > 1,
                onOpenReaction = { editing = null; env.onOpen(TreeStep.Button(key, r, c)) },
                onUpdate = { nb -> onChange(msg.copy(text = field.text, buttons = GridOps.update(msg.buttons, r, c, nb))) },
                onMove = { dir ->
                    val (rows, pos) = GridOps.move(msg.buttons, r, c, dir)
                    onChange(msg.copy(text = field.text, buttons = rows))
                    editing = pos
                },
                onAddBeside = {
                    val (rows, pos) = GridOps.addBeside(msg.buttons, r, c)
                    onChange(msg.copy(text = field.text, buttons = rows))
                    editing = pos
                },
                onDelete = {
                    onChange(msg.copy(text = field.text, buttons = GridOps.delete(msg.buttons, r, c)))
                    editing = null
                },
                onDismiss = {
                    // Botão novo e vazio, fechado sem preencher: some.
                    if (button.text.isBlank() && button.url.isBlank()) {
                        onChange(msg.copy(text = field.text, buttons = GridOps.delete(msg.buttons, r, c)))
                    }
                    editing = null
                },
            )
        }
    }
}

@Composable
private fun ToolIcon(icon: Int, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        AppIcon(icon, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant, description = stringResource(label))
    }
}

@Composable
private fun MediaChip(botId: Long, type: String, file: String, name: String, onRemove: () -> Unit) {
    val path = if (File(file).isAbsolute) file else File(BotRepository.dir(botId), file).path
    val thumb = remember(path) {
        if (type == "photo") runCatching {
            val o = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(path, o)
        }.getOrNull() else null
    }
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else AppIcon(mediaIcon(type) ?: R.drawable.ic_description, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(previewText(type, ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove) { AppIcon(R.drawable.ic_close, description = stringResource(R.string.delete)) }
        }
    }
}

// -- Grade de botões -------------------------------------------------------------------------------

/** Botões na mesma grade que a pessoa vai ver no Telegram. */
@Composable
private fun ButtonGrid(
    rows: List<List<LinkButton>>,
    nodeIds: Set<String>,
    callbackCounts: Map<String, Int>,
    onOpen: (Int, Int) -> Unit,
    onEdit: (Int, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEachIndexed { c, b ->
                    val isCallback = b.type == ButtonTypes.CALLBACK
                    val ok = ButtonTypes.isValid(b, nodeIds) && (!isCallback || (callbackCounts[b.url] ?: 0) <= 1)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        border = if (ok) null else BorderStroke(1.5.dp, MaterialTheme.colorScheme.error),
                        // Callback: tocar no nome abre a pasta com a reação; os três pontinhos, as configurações.
                        modifier = Modifier.weight(1f).clickable { if (isCallback) onOpen(r, c) else onEdit(r, c) },
                    ) {
                        Row(Modifier.padding(start = 8.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(
                                if (isCallback) (if (b.reaction?.blocks.isNullOrEmpty()) R.drawable.ic_folder else R.drawable.ic_folder_open)
                                else buttonTypeIcon(b.type),
                                Modifier.size(16.dp),
                            )
                            Text(
                                b.text.ifBlank { stringResource(R.string.block_button_untitled) },
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                            )
                            IconButton(onClick = { onEdit(r, c) }, modifier = Modifier.size(32.dp)) {
                                AppIcon(R.drawable.ic_more_vert, Modifier.size(18.dp), description = stringResource(R.string.block_button_settings))
                            }
                        }
                    }
                }
            }
        }
    }
}

enum class GridDir { LEFT, RIGHT, UP, DOWN }

/** Operações na grade de botões (linhas × colunas, até 8 por linha). Devolvem a nova posição. */
object GridOps {
    private const val MAX_ROW = 8

    private fun clean(rows: List<List<LinkButton>>) = rows.filter { it.isNotEmpty() }

    fun update(rows: List<List<LinkButton>>, r: Int, c: Int, b: LinkButton) =
        rows.mapIndexed { i, row -> if (i == r) row.mapIndexed { j, x -> if (j == c) b else x } else row }

    fun delete(rows: List<List<LinkButton>>, r: Int, c: Int) =
        clean(rows.mapIndexed { i, row -> if (i == r) row.filterIndexed { j, _ -> j != c } else row })

    fun addRow(rows: List<List<LinkButton>>): Pair<List<List<LinkButton>>, Pair<Int, Int>> =
        (rows + listOf(listOf(LinkButton("", "")))) to (rows.size to 0)

    fun addBeside(rows: List<List<LinkButton>>, r: Int, c: Int): Pair<List<List<LinkButton>>, Pair<Int, Int>> {
        val m = rows.map { it.toMutableList() }.toMutableList()
        return if (m[r].size < MAX_ROW) {
            m[r].add(c + 1, LinkButton("", ""))
            m to (r to c + 1)
        } else {
            m.add(r + 1, mutableListOf(LinkButton("", "")))
            m to (r + 1 to 0)
        }
    }

    fun move(rows: List<List<LinkButton>>, r: Int, c: Int, dir: GridDir): Pair<List<List<LinkButton>>, Pair<Int, Int>> {
        val m = rows.map { it.toMutableList() }.toMutableList()
        val row = m[r]
        when (dir) {
            GridDir.LEFT -> if (c > 0) { row.add(c - 1, row.removeAt(c)); return m to (r to c - 1) }
            GridDir.RIGHT -> if (c < row.lastIndex) { row.add(c + 1, row.removeAt(c)); return m to (r to c + 1) }
            GridDir.UP -> if (row.size > 1) {
                val b = row.removeAt(c)
                m.add(r, mutableListOf(b))
                return m to (r to 0)
            } else if (r > 0 && m[r - 1].size < MAX_ROW) {
                val b = row.removeAt(0)
                m[r - 1].add(b)
                val pos = r - 1 to m[r - 1].lastIndex
                return clean(m) to pos
            }
            GridDir.DOWN -> if (row.size > 1) {
                val b = row.removeAt(c)
                m.add(r + 1, mutableListOf(b))
                return m to (r + 1 to 0)
            } else if (r < m.lastIndex && m[r + 1].size < MAX_ROW) {
                val b = row.removeAt(0)
                m[r + 1].add(0, b)
                return clean(m) to (r to 0)
            }
        }
        return rows to (r to c)
    }
}

// -- Configurações de um botão ---------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ButtonSheet(
    button: LinkButton,
    rowSize: Int,
    nodes: List<FlowNode>,
    duplicateCallback: Boolean,
    onOpenReaction: () -> Unit,
    onUpdate: (LinkButton) -> Unit,
    onMove: (GridDir) -> Unit,
    onAddBeside: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val types = ButtonTypes.ALL.filter { it != ButtonTypes.NODE || nodes.isNotEmpty() || button.type == ButtonTypes.NODE }
    val nodeIds = nodes.map { it.id }.toSet()
    val invalid = button.url.isNotBlank() && !ButtonTypes.isValid(button.copy(text = "x"), nodeIds)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.imePadding().padding(start = 16.dp, end = 16.dp, bottom = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.block_button_settings), style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                types.forEach { t ->
                    FilterChip(
                        selected = button.type == t,
                        onClick = {
                            if (button.type != t) onUpdate(button.copy(type = t, url = when {
                                t in ButtonTypes.LINKS && button.type in ButtonTypes.LINKS -> button.url
                                t == ButtonTypes.CALLBACK -> callbackSlug(button.text)
                                else -> ""
                            }))
                        },
                        label = { Text(buttonTypeLabel(t)) },
                        leadingIcon = { AppIcon(buttonTypeIcon(t), Modifier.size(16.dp)) },
                    )
                }
            }
            OutlinedTextField(
                value = button.text,
                onValueChange = { onUpdate(button.copy(text = it.take(64))) },
                label = { Text(stringResource(R.string.btn_text)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (button.type == ButtonTypes.NODE) {
                Text(stringResource(R.string.btn_node_pick), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    nodes.forEach { n ->
                        FilterChip(selected = button.url == n.id, onClick = { onUpdate(button.copy(url = n.id)) }, label = { Text(n.title.ifBlank { n.id }) })
                    }
                }
            } else {
                OutlinedTextField(
                    value = button.url,
                    onValueChange = { onUpdate(button.copy(url = if (button.type == ButtonTypes.COPY) it.take(256) else it.trim())) },
                    label = { Text(buttonValueLabel(button.type)) },
                    placeholder = { Text(buttonValueHint(button.type)) },
                    singleLine = button.type != ButtonTypes.COPY,
                    isError = invalid || duplicateCallback,
                    supportingText = {
                        Text(
                            when {
                                duplicateCallback -> stringResource(R.string.btn_err_callback_taken)
                                invalid -> buttonValueError(button.type)
                                else -> buttonValueHelp(button.type)
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (button.type == ButtonTypes.CALLBACK) {
                Button(onClick = onOpenReaction, modifier = Modifier.fillMaxWidth()) {
                    ButtonLabel(R.drawable.ic_folder_open, stringResource(R.string.btn_open_reaction))
                }
            }

            HorizontalDivider()
            Text(stringResource(R.string.block_button_position), style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { onMove(GridDir.LEFT) }, contentPadding = PaddingValues(8.dp), modifier = Modifier.size(44.dp)) {
                    AppIcon(R.drawable.ic_chevron_left, description = stringResource(R.string.block_move_left))
                }
                OutlinedButton(onClick = { onMove(GridDir.RIGHT) }, contentPadding = PaddingValues(8.dp), modifier = Modifier.size(44.dp)) {
                    AppIcon(R.drawable.ic_chevron_right, description = stringResource(R.string.block_move_right))
                }
                OutlinedButton(onClick = { onMove(GridDir.UP) }, contentPadding = PaddingValues(8.dp), modifier = Modifier.size(44.dp)) {
                    AppIcon(R.drawable.ic_arrow_upward, description = stringResource(R.string.cmd_move_up))
                }
                OutlinedButton(onClick = { onMove(GridDir.DOWN) }, contentPadding = PaddingValues(8.dp), modifier = Modifier.size(44.dp)) {
                    AppIcon(R.drawable.ic_arrow_downward, description = stringResource(R.string.cmd_move_down))
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.block_button_position_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rowSize < 8) {
                    OutlinedButton(onClick = onAddBeside) { ButtonLabel(R.drawable.ic_add, stringResource(R.string.block_button_beside)) }
                }
                TextButton(onClick = onDelete) {
                    AppIcon(R.drawable.ic_delete, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.done)) }
        }
    }
}

/** Texto de callback sugerido a partir do nome do botão ("Ver planos" -> "ver_planos"). */
fun callbackSlug(text: String): String {
    val base = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), "_").trim('_')
    return base.take(40)
}

// -- Bloco: Aguardar resposta ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WaitBlockCard(env: BlockEditorEnv, block: CommandBlock.Wait, onChange: (CommandBlock.Wait) -> Unit, actions: BlockActions) {
    var editingRule by remember(block.key) { mutableStateOf<Int?>(null) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val waitSample = stringResource(R.string.code_sample_wait)
            BlockHeader(R.drawable.ic_hearing, stringResource(R.string.block_wait), actions) { dismiss ->
                DropdownMenuItem(
                    text = { Text(stringResource(if (block.usesCode) R.string.wait_use_rules else R.string.wait_use_code)) },
                    leadingIcon = { AppIcon(if (block.usesCode) R.drawable.ic_hearing else R.drawable.ic_code) },
                    onClick = { dismiss(); onChange(block.copy(code = if (block.usesCode) "" else waitSample)) },
                )
                HorizontalDivider()
            }
            if (block.usesCode) Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.block_wait_code_help), style = MaterialTheme.typography.bodySmall)
                CodeEditor(block.code, { onChange(block.copy(code = it)) })
                WaitVariableField(block, onChange)
            } else Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.block_wait_help), style = MaterialTheme.typography.bodySmall)
                block.rules.forEachIndexed { i, rule ->
                    RuleRow(
                        prefix = stringResource(if (i == 0) R.string.rule_if else R.string.rule_else_if),
                        condition = rule.condition,
                        count = rule.blocks.size,
                        keep = rule.keep,
                        onCondition = { editingRule = i },
                        onOpen = { env.onOpen(TreeStep.Rule(block.key, i)) },
                    )
                }
                RuleRow(
                    prefix = stringResource(R.string.rule_otherwise),
                    condition = null,
                    count = block.otherwise.size,
                    keep = block.otherwiseKeep,
                    onCondition = null,
                    onOpen = { env.onOpen(TreeStep.Otherwise(block.key)) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onChange(block.copy(rules = block.rules + WaitRule())); editingRule = block.rules.size }) {
                        ButtonLabel(R.drawable.ic_add, stringResource(R.string.rule_add))
                    }
                }
                WaitVariableField(block, onChange)
            }
        }
    }
    editingRule?.let { i ->
        val rule = block.rules.getOrNull(i)
        if (rule == null) editingRule = null
        else RuleSheet(
            rule = rule,
            onChange = { r -> onChange(block.copy(rules = block.rules.mapIndexed { j, x -> if (j == i) r else x })) },
            onDelete = { onChange(block.copy(rules = block.rules.filterIndexed { j, _ -> j != i })); editingRule = null },
            onOpen = { editingRule = null; env.onOpen(TreeStep.Rule(block.key, i)) },
            onDismiss = { editingRule = null },
        )
    }
}

@Composable
private fun WaitVariableField(block: CommandBlock.Wait, onChange: (CommandBlock.Wait) -> Unit) {
    OutlinedTextField(
        value = block.variable,
        onValueChange = { onChange(block.copy(variable = it.lowercase().replace(' ', '_').filter { c -> c.isLetterOrDigit() || c == '_' }.take(32))) },
        label = { Text(stringResource(R.string.wait_variable)) },
        placeholder = { Text(stringResource(R.string.wait_variable_placeholder)) },
        supportingText = {
            Text(stringResource(R.string.wait_variable_hint, block.variable.ifBlank { stringResource(R.string.wait_variable_generic) }))
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

// -- Bloco: Formulário -------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormBlockCard(botId: Long, block: CommandBlock.Form, onChange: (CommandBlock.Form) -> Unit, actions: BlockActions) {
    var showResponses by remember { mutableStateOf(false) }
    fun setQ(i: Int, q: io.github.alerrandrov.easytelebot.data.FormQuestion) =
        onChange(block.copy(questions = block.questions.mapIndexed { j, x -> if (j == i) q else x }))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BlockHeader(R.drawable.ic_list_alt, stringResource(R.string.block_form), actions) { dismiss ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.form_responses)) },
                    leadingIcon = { AppIcon(R.drawable.ic_visibility) },
                    onClick = { dismiss(); showResponses = true },
                )
                HorizontalDivider()
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(block.title, { onChange(block.copy(title = it.take(100))) },
                    label = { Text(stringResource(R.string.form_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                block.questions.forEachIndexed { i, q ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.form_question_n, i + 1), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                if (i > 0) IconButton(onClick = {
                                    onChange(block.copy(questions = block.questions.toMutableList().apply { add(i - 1, removeAt(i)) }))
                                }, modifier = Modifier.size(32.dp)) { AppIcon(R.drawable.ic_arrow_upward, Modifier.size(18.dp)) }
                                if (block.questions.size > 1) IconButton(onClick = {
                                    onChange(block.copy(questions = block.questions.filterIndexed { j, _ -> j != i }))
                                }, modifier = Modifier.size(32.dp)) { AppIcon(R.drawable.ic_delete, Modifier.size(18.dp)) }
                            }
                            OutlinedTextField(q.text, { setQ(i, q.copy(text = it.take(1000))) },
                                label = { Text(stringResource(R.string.form_question)) }, modifier = Modifier.fillMaxWidth())
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                io.github.alerrandrov.easytelebot.data.FormQuestion.KINDS.forEach { k ->
                                    FilterChip(selected = q.kind == k, onClick = { setQ(i, q.copy(kind = k)) }, label = {
                                        Text(stringResource(when (k) {
                                            "number" -> R.string.flow_validate_number
                                            "email" -> R.string.flow_validate_email
                                            "phone" -> R.string.flow_validate_phone
                                            "choice" -> R.string.form_kind_choice
                                            else -> R.string.flow_validate_text
                                        }))
                                    })
                                }
                            }
                            if (q.kind == "choice") WordsInput(q.options, stringResource(R.string.form_option), { setQ(i, q.copy(options = it.take(12))) },
                                splitOnComma = true)
                            OutlinedTextField(q.variable, {
                                setQ(i, q.copy(variable = it.lowercase().replace(' ', '_').filter { c -> c.isLetterOrDigit() || c == '_' }.take(32)))
                            }, label = { Text(stringResource(R.string.wait_variable)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                if (block.questions.size < 30) TextButton(onClick = { onChange(block.copy(questions = block.questions + io.github.alerrandrov.easytelebot.data.FormQuestion())) }) {
                    ButtonLabel(R.drawable.ic_add, stringResource(R.string.form_add_question))
                }
                OutlinedTextField(block.done, { onChange(block.copy(done = it.take(2000))) },
                    label = { Text(stringResource(R.string.form_done)) }, supportingText = { Text(stringResource(R.string.form_done_hint)) },
                    minLines = 2, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.form_notify), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = block.notify, onCheckedChange = { onChange(block.copy(notify = it)) })
                }
                OutlinedButton(onClick = { showResponses = true }) { ButtonLabel(R.drawable.ic_visibility, stringResource(R.string.form_responses)) }
            }
        }
    }
    if (showResponses) FormResponsesDialog(botId, block) { showResponses = false }
}

@Composable
private fun FormResponsesDialog(botId: Long, block: CommandBlock.Form, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val dir = io.github.alerrandrov.easytelebot.data.BotRepository.dir(botId).path
    var items by remember { mutableStateOf<List<io.github.alerrandrov.easytelebot.engine.PythonBridge.FormResponse>?>(null) }
    androidx.compose.runtime.LaunchedEffect(block.id) {
        items = withContext(Dispatchers.IO) { io.github.alerrandrov.easytelebot.engine.PythonBridge.formResponses(context, dir, block.id) }
    }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val csv = io.github.alerrandrov.easytelebot.engine.PythonBridge.formCsv(context, dir, block.id, block.questions.map { it.text })
                    context.contentResolver.openOutputStream(uri)?.use { it.write(("\uFEFF" + csv).toByteArray()) }
                }.isSuccess
            }
            Toast.makeText(context, context.getString(if (ok) R.string.form_exported else R.string.export_failed), Toast.LENGTH_SHORT).show()
        }
    }
    val fmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.form_responses_n, items?.size ?: 0)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val list = items
                if (list == null) Text("…")
                else if (list.isEmpty()) Text(stringResource(R.string.form_no_responses))
                else list.forEach { r ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Text(r.name + (if (r.username.isNotBlank()) " (@${r.username})" else "") + " · " + fmt.format(java.util.Date(r.at)),
                                style = MaterialTheme.typography.labelMedium)
                            block.questions.zip(r.answers).forEach { (q, a) ->
                                Text(q.text.take(60) + ": " + a, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !items.isNullOrEmpty(), onClick = { saveCsv.launch((block.title.ifBlank { "form" }) + ".csv") }) {
                Text(stringResource(R.string.form_export))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

// -- Bloco: Requisição HTTP ------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HttpBlockCard(block: CommandBlock.Http, onChange: (CommandBlock.Http) -> Unit, actions: BlockActions) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var advanced by remember(block.key) { mutableStateOf(block.headers.isNotBlank() || block.body.isNotBlank()) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<io.github.alerrandrov.easytelebot.engine.PythonBridge.HttpTest?>(null) }
    val variable = block.variable.ifBlank { "http" }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BlockHeader(R.drawable.ic_public, stringResource(R.string.block_http), actions) { dismiss ->
                listOf(5, 15, 30).forEach { t ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.block_termux_timeout, t)) },
                        leadingIcon = { if (block.timeout == t) AppIcon(R.drawable.ic_check) },
                        onClick = { dismiss(); onChange(block.copy(timeout = t)) },
                    )
                }
                HorizontalDivider()
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CommandBlock.Http.METHODS.forEach { m ->
                        FilterChip(selected = block.method == m, onClick = { onChange(block.copy(method = m)) }, label = { Text(m) })
                    }
                }
                OutlinedTextField(
                    value = block.url,
                    onValueChange = { onChange(block.copy(url = it.take(2000).replace("\n", ""))) },
                    label = { Text(stringResource(R.string.http_url)) },
                    placeholder = { Text(stringResource(R.string.http_url_placeholder)) },
                    supportingText = { Text(stringResource(R.string.http_url_hint)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = block.variable,
                    onValueChange = { onChange(block.copy(variable = it.lowercase().replace(' ', '_').filter { c -> c.isLetterOrDigit() || c == '_' }.take(32))) },
                    label = { Text(stringResource(R.string.http_variable)) },
                    placeholder = { Text("http") },
                    supportingText = { Text(stringResource(R.string.http_variable_hint, variable)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = block.path,
                    onValueChange = { onChange(block.copy(path = it.take(200))) },
                    label = { Text(stringResource(R.string.http_path)) },
                    placeholder = { Text("data.price") },
                    supportingText = { Text(stringResource(R.string.http_path_hint)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { advanced = !advanced }) {
                    ButtonLabel(if (advanced) R.drawable.ic_expand_more else R.drawable.ic_chevron_right, stringResource(R.string.http_advanced))
                }
                if (advanced) {
                    OutlinedTextField(
                        value = block.headers,
                        onValueChange = { onChange(block.copy(headers = it.take(4000))) },
                        label = { Text(stringResource(R.string.http_headers)) },
                        placeholder = { Text("Authorization: Bearer …") },
                        minLines = 2,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (block.method != "GET") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CommandBlock.Http.BODY_TYPES.forEach { t ->
                                FilterChip(selected = block.bodyType == t, onClick = { onChange(block.copy(bodyType = t)) },
                                    label = { Text(stringResource(when (t) { "form" -> R.string.http_body_form; "text" -> R.string.http_body_text; else -> R.string.http_body_json })) })
                            }
                        }
                        OutlinedTextField(
                            value = block.body,
                            onValueChange = { onChange(block.copy(body = it.take(20000))) },
                            label = { Text(stringResource(R.string.http_body)) },
                            placeholder = { Text(if (block.bodyType == "form") "nome={nome}&id={id}" else "{\"nome\": \"{nome}\"}") },
                            minLines = 3,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = block.url.isNotBlank() && !testing, onClick = {
                        testing = true
                        scope.launch {
                            result = withContext(Dispatchers.IO) {
                                io.github.alerrandrov.easytelebot.engine.PythonBridge.testHttp(context, CommandBlock.toJson(block).toString())
                            }
                            testing = false
                        }
                    }) { ButtonLabel(R.drawable.ic_play_arrow, stringResource(if (testing) R.string.shop_testing else R.string.http_test)) }
                }
                result?.let { r ->
                    Card(colors = CardDefaults.cardColors(containerColor = if (r.ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (r.status > 0) stringResource(R.string.http_test_status, r.status) else stringResource(R.string.http_test_failed, r.error),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            if (r.value.isNotBlank()) Text("{$variable} = " + r.value.take(500), style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            if (block.path.isNotBlank() && r.value.isBlank() && r.preview.isNotBlank()) {
                                Text(stringResource(R.string.http_path_missing), style = MaterialTheme.typography.bodySmall)
                                Text(r.preview.take(800), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}

// -- Bloco: Python facilitado ------------------------------------------------------------------------

@Composable
private fun CodeBlockCard(code: String, onChange: (String) -> Unit, actions: BlockActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BlockHeader(R.drawable.ic_code, stringResource(R.string.block_code), actions)
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.block_code_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                CodeEditor(code, onChange)
            }
        }
    }
}

/** "SE [Tipo da mensagem] [é] [Foto]  → pasta (n)". Os bloquinhos da condição abrem a edição. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleRow(prefix: String, condition: WaitCondition?, count: Int, keep: Boolean, onCondition: (() -> Unit)?, onOpen: () -> Unit) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LogicChip(prefix, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, null)
                if (condition != null && onCondition != null) {
                    conditionParts(condition).forEach { part ->
                        LogicChip(part, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, onCondition)
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(if (count > 0) R.drawable.ic_folder_open else R.drawable.ic_folder, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (count > 0) stringResource(R.string.rule_reply_n, count) else stringResource(R.string.rule_reply_empty),
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
                    )
                    if (keep) {
                        AppIcon(R.drawable.ic_refresh, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary,
                            description = stringResource(R.string.rule_keep))
                    }
                    AppIcon(R.drawable.ic_chevron_right, Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun LogicChip(text: String, bg: Color, fg: Color, onClick: (() -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(6.dp), color = bg, contentColor = fg,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun conditionParts(c: WaitCondition): List<String> = when (c.field) {
    "any" -> listOf(stringResource(R.string.cond_any))
    "type" -> listOf(
        stringResource(R.string.cond_type),
        stringResource(if (c.op == "is_not") R.string.cond_is_not else R.string.cond_is),
        messageTypeLabel(c.value),
    )
    else -> listOfNotNull(
        stringResource(R.string.cond_text),
        textOpLabel(c.op),
        if (c.op in WaitCondition.NO_VALUE) null else "\"" + c.value + "\"",
    )
}

@Composable
private fun messageTypeLabel(type: String): String = when (type) {
    "text" -> stringResource(R.string.cond_type_text)
    "animation" -> stringResource(R.string.media_animation)
    else -> previewText(type, "").ifBlank { type }
}

@Composable
private fun textOpLabel(op: String): String = stringResource(
    when (op) {
        "equals" -> R.string.cond_equals
        "starts" -> R.string.cond_starts
        "ends" -> R.string.cond_ends
        "number" -> R.string.cond_number
        "email" -> R.string.cond_email
        "phone" -> R.string.cond_phone
        else -> R.string.cond_contains
    },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RuleSheet(rule: WaitRule, onChange: (WaitRule) -> Unit, onDelete: () -> Unit, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val c = rule.condition
    fun set(cond: WaitCondition) = onChange(rule.copy(condition = cond))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.imePadding().padding(start = 16.dp, end = 16.dp, bottom = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.rule_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.rule_when), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("type" to R.string.cond_type, "text" to R.string.cond_text, "any" to R.string.cond_any).forEach { (f, label) ->
                    FilterChip(selected = c.field == f, onClick = {
                        if (c.field != f) set(when (f) {
                            "type" -> WaitCondition("type", "is", "photo")
                            "text" -> WaitCondition("text", "contains", "")
                            else -> WaitCondition("any", "is", "")
                        })
                    }, label = { Text(stringResource(label)) })
                }
            }
            when (c.field) {
                "type" -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = c.op != "is_not", onClick = { set(c.copy(op = "is")) }, label = { Text(stringResource(R.string.cond_is)) })
                        FilterChip(selected = c.op == "is_not", onClick = { set(c.copy(op = "is_not")) }, label = { Text(stringResource(R.string.cond_is_not)) })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WaitCondition.TYPES.forEach { t ->
                            FilterChip(
                                selected = c.value == t, onClick = { set(c.copy(value = t)) },
                                label = { Text(messageTypeLabel(t)) },
                                leadingIcon = { AppIcon(mediaIcon(t) ?: R.drawable.ic_chat, Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                "text" -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WaitCondition.TEXT_OPS.forEach { op ->
                            FilterChip(selected = c.op == op, onClick = { set(c.copy(op = op)) }, label = { Text(textOpLabel(op)) })
                        }
                    }
                    if (c.op !in WaitCondition.NO_VALUE) {
                        OutlinedTextField(
                            value = c.value, onValueChange = { set(c.copy(value = it.take(200))) },
                            label = { Text(stringResource(R.string.cond_value)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text(stringResource(R.string.cond_value_hint)) },
                        )
                    }
                }
                else -> Text(stringResource(R.string.cond_any_hint), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            SwitchRow(stringResource(R.string.rule_keep), stringResource(R.string.rule_keep_sub), rule.keep) { onChange(rule.copy(keep = it)) }
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                ButtonLabel(R.drawable.ic_folder_open, stringResource(R.string.rule_open))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDelete) {
                    AppIcon(R.drawable.ic_delete, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.rule_delete), color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
            }
        }
    }
}

// -- Bloco: Termux -----------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TermuxBlockCard(script: CommandScript, onChange: (CommandScript) -> Unit, actions: BlockActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BlockHeader(R.drawable.ic_terminal, stringResource(R.string.block_termux), actions) { dismiss ->
                listOf(10, 30, 60, 120).forEach { t ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.block_termux_timeout, t)) },
                        leadingIcon = { if (script.timeout == t) AppIcon(R.drawable.ic_check) },
                        onClick = { dismiss(); onChange(script.copy(timeout = t)) },
                    )
                }
                HorizontalDivider()
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextField(
                    value = script.command,
                    onValueChange = { onChange(script.copy(command = it.take(2000))) },
                    placeholder = { Text(stringResource(R.string.block_termux_placeholder), fontFamily = FontFamily.Monospace) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(12.dp),
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.block_termux_help, script.timeout), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// -- Bloco: fluxo (comandos antigos que iniciavam um fluxo) ---------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowBlockCard(node: String, nodes: List<FlowNode>, onChange: (String) -> Unit, actions: BlockActions, onOpenFlows: (() -> Unit)?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BlockHeader(R.drawable.ic_account_tree, stringResource(R.string.block_flow), actions)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(end = 8.dp)) {
                nodes.forEach { n ->
                    FilterChip(selected = node == n.id, onClick = { onChange(n.id) }, label = { Text(n.title.ifBlank { n.id }) })
                }
            }
            if (nodes.none { it.id == node }) {
                Text(stringResource(R.string.btn_node_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (onOpenFlows != null) {
                TextButton(onClick = onOpenFlows) { ButtonLabel(R.drawable.ic_account_tree, stringResource(R.string.block_flow_open)) }
            }
        }
    }
}

// -- Pré-visualização ---------------------------------------------------------------------------------

/** Como a conversa vai ficar no Telegram, com valores de exemplo nas variáveis. */
@Composable
fun CommandPreviewDialog(trigger: String, blocks: List<CommandBlock>, botId: Long, nodes: List<FlowNode>, asText: Boolean = false,
                         onDismiss: () -> Unit) {
    val now = remember { Date() }
    val first = stringResource(R.string.preview_sample_first)
    val last = stringResource(R.string.preview_sample_last)
    val bot = stringResource(R.string.preview_sample_bot)
    val args = stringResource(R.string.preview_args)
    val sample = remember(first, last, bot, args) {
        // Mesmo formato do motor: data dd/mm/aaaa e hora hh:mm (variáveis em português e inglês).
        val date = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(now)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val user = "@" + first.lowercase()
        mapOf(
            "nome" to first, "sobrenome" to last, "usuario" to user, "id" to "123456789", "grupo" to "",
            "args" to args, "bot" to bot, "data" to date, "hora" to time,
            "name" to first, "last_name" to last, "username" to user, "group" to "", "date" to date, "time" to time,
        )
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.95f), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(R.drawable.ic_visibility, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.preview_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { AppIcon(R.drawable.ic_close, description = stringResource(R.string.close)) }
                }
                Column(
                    Modifier.fillMaxWidth().height(480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Mensagem da pessoa
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary) {
                            Text(if (asText) trigger else "/" + trigger.ifBlank { stringResource(R.string.command_placeholder) }, color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                    if (blocks.isEmpty()) {
                        Text(stringResource(R.string.preview_empty), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    blocks.forEach { b ->
                        when (b) {
                            is CommandBlock.Message -> PreviewBubble(b.msg, botId, sample)
                            is CommandBlock.Termux -> PreviewNote(R.drawable.ic_terminal,
                                stringResource(R.string.preview_termux, b.script.command.ifBlank { "…" }))
                            is CommandBlock.Flow -> PreviewNote(R.drawable.ic_account_tree,
                                stringResource(R.string.preview_flow, nodes.firstOrNull { it.id == b.node }?.title ?: b.node))
                            is CommandBlock.Wait -> PreviewNote(R.drawable.ic_hearing,
                                if (b.usesCode) stringResource(R.string.preview_wait_code) else stringResource(R.string.preview_wait, b.rules.size))
                            is CommandBlock.Code -> PreviewNote(R.drawable.ic_code, stringResource(R.string.preview_code))
                            is CommandBlock.Form -> PreviewNote(R.drawable.ic_list_alt,
                                stringResource(R.string.preview_form, b.questions.size, b.questions.firstOrNull()?.text.orEmpty().take(80)))
                            is CommandBlock.Http -> PreviewNote(R.drawable.ic_public,
                                stringResource(R.string.preview_http, b.method, b.url.take(60), b.variable.ifBlank { "http" }))
                        }
                    }
                }
                Text(stringResource(R.string.preview_note), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PreviewBubble(msg: BotMessage, botId: Long, sample: Map<String, String>) {
    Column(Modifier.widthIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val media = msg.media
                if (media != null) {
                    val path = if (File(media.file).isAbsolute) media.file else File(BotRepository.dir(botId), media.file).path
                    val bmp = remember(path) {
                        if (media.type == "photo") runCatching {
                            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })
                        }.getOrNull() else null
                    }
                    if (bmp != null) {
                        Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop)
                    } else {
                        IconLabel(mediaIcon(media.type) ?: R.drawable.ic_description, media.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (msg.text.isNotBlank()) Text(previewMarkdown(msg.text, sample, MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodyLarge)
            }
        }
        msg.buttons.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { b ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f),
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(Modifier.padding(horizontal = 6.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
                            Text(previewMarkdown(b.text, sample, MaterialTheme.colorScheme.primary).text, style = MaterialTheme.typography.labelLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (b.type !in setOf(ButtonTypes.NODE, ButtonTypes.CALLBACK)) {
                                AppIcon(
                                    if (b.type == ButtonTypes.COPY) R.drawable.ic_content_copy else R.drawable.ic_open_in_new,
                                    Modifier.size(11.dp).align(Alignment.TopEnd),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewNote(icon: Int, text: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icon, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A mesma marcação do motor (`template.py`), para a pré-visualização: *negrito*, **negrito**,
 * _itálico_, __sublinhado__, ~riscado~, ||spoiler||, `código`, ```bloco```, [texto](link) e
 * linhas com "> " (citação). Variáveis trocadas pelos valores de exemplo.
 */
fun previewMarkdown(raw: String, sample: Map<String, String>, accent: Color): AnnotatedString {
    val text = Regex("""\{([a-z_][a-z0-9_]*)\}""").replace(raw) { m -> sample[m.groupValues[1]] ?: "[" + m.groupValues[1] + "]" }
    val rules = listOf(
        Regex("```([\\s\\S]+?)```") to SpanStyle(fontFamily = FontFamily.Monospace),
        Regex("`([^`\\n]+)`") to SpanStyle(fontFamily = FontFamily.Monospace),
        Regex("\\[([^\\]\\n]+)]\\(([^)\\s]+)\\)") to SpanStyle(color = accent, textDecoration = TextDecoration.Underline),
        Regex("\\*\\*(\\S(?:.*?\\S)?)\\*\\*") to SpanStyle(fontWeight = FontWeight.Bold),
        Regex("__(\\S(?:.*?\\S)?)__") to SpanStyle(textDecoration = TextDecoration.Underline),
        Regex("\\|\\|(\\S(?:.*?\\S)?)\\|\\|") to SpanStyle(background = Color.Gray.copy(alpha = 0.5f)),
        Regex("(?<![\\w*])\\*(\\S(?:[^*\\n]*?\\S)?)\\*(?![\\w*])") to SpanStyle(fontWeight = FontWeight.Bold),
        Regex("(?<![\\w_])_(\\S(?:[^_\\n]*?\\S)?)_(?![\\w_])") to SpanStyle(fontStyle = FontStyle.Italic),
        Regex("(?<![\\w~])~(\\S(?:[^~\\n]*?\\S)?)~(?![\\w~])") to SpanStyle(textDecoration = TextDecoration.LineThrough),
    )

    fun inline(builder: AnnotatedString.Builder, s: String) {
        var rest = s
        while (rest.isNotEmpty()) {
            val hit = rules.mapNotNull { (re, style) -> re.find(rest)?.let { it to style } }.minByOrNull { it.first.range.first }
            if (hit == null) {
                builder.append(rest)
                return
            }
            val (m, style) = hit
            builder.append(rest.substring(0, m.range.first))
            val inner = m.groupValues[1]
            val start = builder.length
            if (style.fontFamily == FontFamily.Monospace) builder.append(inner) else inline(builder, inner)
            builder.addStyle(style, start, builder.length)
            rest = rest.substring(m.range.last + 1)
        }
    }

    return buildAnnotatedString {
        text.split("\n").forEachIndexed { i, line ->
            if (i > 0) append("\n")
            if (line.startsWith("> ") || line == ">") {
                val start = length
                inline(this, line.removePrefix(">").removePrefix(" "))
                addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = accent), start, length)
            } else {
                inline(this, line)
            }
        }
    }
}
