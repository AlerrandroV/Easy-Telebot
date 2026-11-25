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
import io.github.alerrandrov.easytelebot.data.BotMessage
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.ButtonTypes
import io.github.alerrandrov.easytelebot.data.CommandBlock
import io.github.alerrandrov.easytelebot.data.CommandScript
import io.github.alerrandrov.easytelebot.data.FlowNode
import io.github.alerrandrov.easytelebot.data.LinkButton
import io.github.alerrandrov.easytelebot.data.MediaImporter
import io.github.alerrandrov.easytelebot.data.Variables
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TEXT_MAX = 4096
private const val CAPTION_MAX = 1024

// -- Lista de blocos ---------------------------------------------------------------------------

/**
 * Resposta de um comando em blocos. Começa vazia, com o botão Adicionar
 * (Mensagem simples ou Termux); cada bloco é um card compacto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResponseBlocksEditor(
    botId: Long,
    blocks: List<CommandBlock>,
    onChange: (List<CommandBlock>) -> Unit,
    nodes: List<FlowNode>,
    extraVariables: Set<String>,
) {
    var showAdd by remember { mutableStateOf(false) }

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
            val actions = BlockActions(
                canUp = i > 0, canDown = i < blocks.lastIndex,
                onUp = { move(i, -1) }, onDown = { move(i, 1) },
                onDuplicate = { duplicate(i) }, onDelete = { remove(i) },
            )
            when (block) {
                is CommandBlock.Message -> MessageBlockCard(
                    botId, block.key, block.msg, { replace(i, block.copy(msg = it)) }, actions, nodes, extraVariables,
                )
                is CommandBlock.Termux -> TermuxBlockCard(block.script, { replace(i, block.copy(script = it)) }, actions)
                is CommandBlock.Flow -> FlowBlockCard(block.node, nodes, { replace(i, block.copy(node = it)) }, actions)
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
                    onClick = { onChange(blocks + CommandBlock.Message(BotMessage())); showAdd = false },
                )
                SectionCard(
                    icon = R.drawable.ic_terminal,
                    title = stringResource(R.string.block_termux),
                    subtitle = stringResource(R.string.block_termux_sub),
                    onClick = { onChange(blocks + CommandBlock.Termux(CommandScript())); showAdd = false },
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
    val onDuplicate: () -> Unit,
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
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.block_duplicate)) },
                    leadingIcon = { AppIcon(R.drawable.ic_content_copy) },
                    onClick = { menu = false; actions.onDuplicate() },
                )
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
    botId: Long,
    key: String,
    msg: BotMessage,
    onChange: (BotMessage) -> Unit,
    actions: BlockActions,
    nodes: List<FlowNode>,
    extraVariables: Set<String>,
) {
    val context = LocalContext.current
    var field by remember(key) { mutableStateOf(TextFieldValue(msg.text)) }
    var editing by remember(key) { mutableStateOf<Pair<Int, Int>?>(null) }
    var formatMenu by remember { mutableStateOf(false) }
    var varsMenu by remember { mutableStateOf(false) }

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
                            (Variables.KNOWN + extraVariables).forEach { v ->
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
                    ButtonGrid(msg.buttons, nodes.map { it.id }.toSet()) { r, c -> editing = r to c }
                }
            }
        }
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
private fun ButtonGrid(rows: List<List<LinkButton>>, nodeIds: Set<String>, onEdit: (Int, Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEachIndexed { c, b ->
                    val ok = ButtonTypes.isValid(b, nodeIds)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        border = if (ok) null else BorderStroke(1.5.dp, MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f).clickable { onEdit(r, c) },
                    ) {
                        Row(Modifier.padding(start = 8.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(buttonTypeIcon(b.type), Modifier.size(16.dp))
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
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.block_button_settings), style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                types.forEach { t ->
                    FilterChip(
                        selected = button.type == t,
                        onClick = { if (button.type != t) onUpdate(button.copy(type = t, url = if (t in ButtonTypes.LINKS && button.type in ButtonTypes.LINKS) button.url else "")) },
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
                    isError = invalid,
                    supportingText = { Text(if (invalid) buttonValueError(button.type) else buttonValueHelp(button.type)) },
                    modifier = Modifier.fillMaxWidth(),
                )
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
                    placeholder = { Text("python ~/bot/clima.py", fontFamily = FontFamily.Monospace) },
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
private fun FlowBlockCard(node: String, nodes: List<FlowNode>, onChange: (String) -> Unit, actions: BlockActions) {
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
        }
    }
}

// -- Pré-visualização ---------------------------------------------------------------------------------

/** Como a conversa vai ficar no Telegram, com valores de exemplo nas variáveis. */
@Composable
fun CommandPreviewDialog(trigger: String, blocks: List<CommandBlock>, botId: Long, nodes: List<FlowNode>, onDismiss: () -> Unit) {
    val now = remember { Date() }
    val sample = mapOf(
        "nome" to "Ana", "sobrenome" to "Silva", "usuario" to "@ana", "id" to "123456789", "grupo" to "",
        "args" to stringResource(R.string.preview_args), "bot" to "@seu_bot",
        "data" to SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(now),
        "hora" to SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
    )
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
                            Text("/" + trigger.ifBlank { "comando" }, color = MaterialTheme.colorScheme.onPrimary,
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
