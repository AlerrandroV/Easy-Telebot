package io.github.alerrandrov.easytelebot.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.ReplyKey
import io.github.alerrandrov.easytelebot.data.ReplyKeyboard
import io.github.alerrandrov.easytelebot.data.SimpleCommand
import io.github.alerrandrov.easytelebot.data.TriggerKinds
import io.github.alerrandrov.easytelebot.data.TriggerSpec

// -- Nomes e ícones ------------------------------------------------------------------------------

fun triggerIcon(kind: String): Int = when (kind) {
    TriggerKinds.KEYWORD -> R.drawable.ic_text_fields
    TriggerKinds.MEDIA -> R.drawable.ic_perm_media
    TriggerKinds.EVENT -> R.drawable.ic_group_add
    TriggerKinds.START -> R.drawable.ic_link
    TriggerKinds.INLINE -> R.drawable.ic_alternate_email
    else -> R.drawable.ic_send
}

@Composable
fun triggerKindLabel(kind: String): String = stringResource(
    when (kind) {
        TriggerKinds.KEYWORD -> R.string.trigger_keyword
        TriggerKinds.MEDIA -> R.string.trigger_media
        TriggerKinds.EVENT -> R.string.trigger_event
        TriggerKinds.START -> R.string.trigger_start
        TriggerKinds.INLINE -> R.string.trigger_inline
        else -> R.string.add_simple_command
    }
)

@Composable
private fun mediaKindLabel(kind: String): String = stringResource(
    when (kind) {
        "photo" -> R.string.media_photo
        "video" -> R.string.media_video
        "animation" -> R.string.media_animation
        "voice" -> R.string.media_voice
        "audio" -> R.string.media_audio
        "video_note" -> R.string.media_video_note
        "document" -> R.string.media_document
        "sticker" -> R.string.media_sticker
        "location" -> R.string.media_location
        "contact" -> R.string.media_contact
        else -> R.string.media_poll
    }
)

@Composable
private fun eventLabel(event: String): String = stringResource(
    when (event) {
        "join" -> R.string.event_join
        "leave" -> R.string.event_leave
        "join_request" -> R.string.event_join_request
        "reaction" -> R.string.event_reaction
        "bot_added" -> R.string.event_bot_added
        else -> R.string.event_bot_promoted
    }
)

@Composable
private fun eventHelp(event: String): String = stringResource(
    when (event) {
        "join" -> R.string.event_join_help
        "leave" -> R.string.event_leave_help
        "join_request" -> R.string.event_join_request_help
        "reaction" -> R.string.event_reaction_help
        "bot_added" -> R.string.event_bot_added_help
        else -> R.string.event_bot_promoted_help
    }
)

/** Resumo do gatilho para o título do editor e a lista do bot: “preço”, Foto, Entrou no grupo… */
@Composable
fun triggerSummary(cmd: SimpleCommand): String {
    val t = cmd.trigger
    return when (cmd.kind) {
        TriggerKinds.KEYWORD -> t.words.joinToString(", ") { "“$it”" }
        TriggerKinds.MEDIA -> t.kinds.map { mediaKindLabel(it) }.joinToString(", ")
        TriggerKinds.EVENT -> eventLabel(t.event)
        TriggerKinds.START -> t.words.joinToString(", ") { "?start=$it" + if (t.prefix) "…" else "" }
        TriggerKinds.INLINE -> t.title.ifBlank { t.words.joinToString(", ") }.ifBlank { stringResource(R.string.trigger_inline_any) }
        else -> cmd.triggers.joinToString("  ·  ") { "/$it" }
    }.ifBlank { triggerKindLabel(cmd.kind) }
}

/** Erros do gatilho (o editor junta com os erros dos blocos). */
fun triggerErrors(context: android.content.Context, kind: String, spec: TriggerSpec, hasMessage: Boolean, hasBlocks: Boolean): List<String> {
    val out = mutableListOf<String>()
    when (kind) {
        TriggerKinds.KEYWORD -> {
            if (spec.words.isEmpty()) out += context.getString(R.string.trigger_err_words)
            if (spec.match == "regex") spec.words.filter { runCatching { Regex(it) }.isFailure }.forEach {
                out += context.getString(R.string.trigger_err_regex, it)
            }
        }
        TriggerKinds.MEDIA -> if (spec.kinds.isEmpty()) out += context.getString(R.string.trigger_err_kinds)
        TriggerKinds.START -> if (spec.words.isEmpty()) out += context.getString(R.string.trigger_err_params)
        TriggerKinds.INLINE -> if (!hasMessage) out += context.getString(R.string.trigger_err_inline)
    }
    val onlyApproves = kind == TriggerKinds.EVENT && spec.event == "join_request" && spec.approve != "none"
    if (!hasBlocks && kind != TriggerKinds.INLINE && !onlyApproves) out += context.getString(R.string.cmd_err_no_blocks)
    return out
}

// -- Editor ------------------------------------------------------------------------------------------

/** O "quando" de um gatilho automático (o "o que fazer" são os blocos de sempre). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TriggerEditor(kind: String, spec: TriggerSpec, botUsername: String, enabled: Boolean, onEnabled: (Boolean) -> Unit,
                  onChange: (TriggerSpec) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(triggerIcon(kind), Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(triggerKindLabel(kind), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onEnabled)
            }
            Text(
                stringResource(
                    when (kind) {
                        TriggerKinds.KEYWORD -> R.string.trigger_keyword_help
                        TriggerKinds.MEDIA -> R.string.trigger_media_help
                        TriggerKinds.EVENT -> R.string.trigger_event_help
                        TriggerKinds.START -> R.string.trigger_start_help
                        else -> R.string.trigger_inline_help
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (kind) {
                TriggerKinds.KEYWORD -> KeywordFields(spec, onChange)
                TriggerKinds.MEDIA -> {
                    Label(stringResource(R.string.trigger_media_kinds))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TriggerKinds.MEDIA_KINDS.forEach { k ->
                            FilterChip(
                                selected = k in spec.kinds,
                                onClick = { onChange(spec.copy(kinds = if (k in spec.kinds) spec.kinds - k else spec.kinds + k)) },
                                label = { Text(mediaKindLabel(k)) },
                            )
                        }
                    }
                    WhereChips(spec, onChange)
                }
                TriggerKinds.EVENT -> EventFields(spec, onChange)
                TriggerKinds.START -> StartFields(spec, botUsername, onChange)
                TriggerKinds.INLINE -> InlineFields(spec, botUsername, onChange)
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.titleSmall)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceChips(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
private fun WhereChips(spec: TriggerSpec, onChange: (TriggerSpec) -> Unit) {
    Label(stringResource(R.string.trigger_where))
    ChoiceChips(TriggerKinds.WHERE, spec.where, { w ->
        stringResource(
            when (w) {
                "private" -> R.string.trigger_where_private
                "groups" -> R.string.trigger_where_groups
                else -> R.string.trigger_where_all
            }
        )
    }) { onChange(spec.copy(where = it)) }
}

/** Lista de palavras em chips, com campo para adicionar (Enter ou vírgula). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordsInput(
    words: List<String>,
    label: String,
    onChange: (List<String>) -> Unit,
    prefix: String = "",
    valid: (String) -> Boolean = { true },
    invalidText: String? = null,
    splitOnComma: Boolean = true,
) {
    var input by remember { mutableStateOf("") }
    fun add() {
        val parts = (if (splitOnComma) input.split(',', '\n') else listOf(input)).map { it.trim() }.filter { it.isNotEmpty() }
        val ok = parts.filter(valid)
        if (ok.isNotEmpty()) onChange((words + ok).distinct().take(50))
        input = parts.filterNot(valid).joinToString(", ")
    }
    val bad = input.isNotBlank() && !valid(input.trim())
    if (words.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            words.forEach { w ->
                InputChip(
                    selected = false,
                    onClick = { onChange(words - w) },
                    label = { Text(prefix + w, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { AppIcon(R.drawable.ic_close, Modifier.size(16.dp)) },
                )
            }
        }
    }
    OutlinedTextField(
        value = input,
        onValueChange = { input = it.take(200) },
        label = { Text(label) },
        singleLine = true,
        isError = bad,
        supportingText = { if (bad && invalidText != null) Text(invalidText) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { add() }),
        trailingIcon = { TextButton(onClick = { add() }, enabled = input.isNotBlank()) { Text(stringResource(R.string.add)) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun KeywordFields(spec: TriggerSpec, onChange: (TriggerSpec) -> Unit) {
    WordsInput(spec.words, stringResource(R.string.trigger_keyword_words), { onChange(spec.copy(words = it)) },
        valid = { w -> spec.match != "regex" || runCatching { Regex(w) }.isSuccess },
        invalidText = stringResource(R.string.trigger_regex_invalid),
        splitOnComma = spec.match != "regex")
    Label(stringResource(R.string.trigger_match))
    ChoiceChips(TriggerKinds.MATCHES, spec.match, { m ->
        stringResource(
            when (m) {
                "word" -> R.string.trigger_match_word
                "starts" -> R.string.trigger_match_starts
                "equals" -> R.string.trigger_match_equals
                "regex" -> R.string.trigger_match_regex
                else -> R.string.trigger_match_contains
            }
        )
    }) { onChange(spec.copy(match = it)) }
    Text(
        stringResource(
            when (spec.match) {
                "word" -> R.string.trigger_match_word_help
                "starts" -> R.string.trigger_match_starts_help
                "equals" -> R.string.trigger_match_equals_help
                "regex" -> R.string.trigger_match_regex_help
                else -> R.string.trigger_match_contains_help
            }
        ),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SwitchRowCompact(stringResource(R.string.trigger_case), spec.caseSensitive) { onChange(spec.copy(caseSensitive = it)) }
    WhereChips(spec, onChange)
    if (spec.where != "private") Note(stringResource(R.string.trigger_privacy_note))
}

@Composable
private fun EventFields(spec: TriggerSpec, onChange: (TriggerSpec) -> Unit) {
    Label(stringResource(R.string.trigger_event_pick))
    ChoiceChips(TriggerKinds.EVENTS, spec.event, { eventLabel(it) }) { onChange(spec.copy(event = it)) }
    Text(eventHelp(spec.event), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    when (spec.event) {
        "join_request" -> {
            Label(stringResource(R.string.trigger_approve))
            ChoiceChips(TriggerKinds.APPROVE, spec.approve, { a ->
                stringResource(
                    when (a) {
                        "approve" -> R.string.trigger_approve_yes
                        "decline" -> R.string.trigger_approve_no
                        else -> R.string.trigger_approve_none
                    }
                )
            }) { onChange(spec.copy(approve = it)) }
        }
        "reaction" -> {
            WordsInput(spec.emojis, stringResource(R.string.trigger_emojis), { onChange(spec.copy(emojis = it.take(20))) })
            Text(stringResource(R.string.trigger_emojis_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            WhereChips(spec, onChange)
        }
    }
    if (spec.event in listOf("join_request", "reaction", "bot_promoted")) Note(stringResource(R.string.trigger_admin_note))
}

@Composable
private fun StartFields(spec: TriggerSpec, botUsername: String, onChange: (TriggerSpec) -> Unit) {
    val context = LocalContext.current
    WordsInput(spec.words, stringResource(R.string.trigger_start_params), { onChange(spec.copy(words = it)) },
        prefix = "?start=", valid = { TriggerKinds.START_PARAM.matches(it) },
        invalidText = stringResource(R.string.trigger_start_invalid))
    SwitchRowCompact(stringResource(R.string.trigger_start_prefix), spec.prefix) { onChange(spec.copy(prefix = it)) }
    if (spec.prefix) Text(stringResource(R.string.trigger_start_prefix_help), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    val first = spec.words.firstOrNull()
    if (first != null && botUsername.isNotBlank()) {
        val link = "https://t.me/$botUsername?start=$first" + if (spec.prefix) "123" else ""
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(link, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("link", link))
                Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
            }) { AppIcon(R.drawable.ic_content_copy, description = stringResource(R.string.copy)) }
        }
    }
}

@Composable
private fun InlineFields(spec: TriggerSpec, botUsername: String, onChange: (TriggerSpec) -> Unit) {
    OutlinedTextField(
        value = spec.title,
        onValueChange = { onChange(spec.copy(title = it.take(64))) },
        label = { Text(stringResource(R.string.trigger_inline_title)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = spec.subtitle,
        onValueChange = { onChange(spec.copy(subtitle = it.take(128))) },
        label = { Text(stringResource(R.string.trigger_inline_subtitle)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    WordsInput(spec.words, stringResource(R.string.trigger_inline_words), { onChange(spec.copy(words = it)) })
    Text(stringResource(R.string.trigger_inline_words_hint, botUsername.ifBlank { "bot" }),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Note(stringResource(R.string.trigger_inline_note))
}

@Composable
private fun SwitchRowCompact(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Note(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(10.dp)) {
            AppIcon(R.drawable.ic_info, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

// -- Lista na tela do bot ----------------------------------------------------------------------------

@Composable
fun AutomationCard(cmd: SimpleCommand, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(triggerIcon(cmd.kind), Modifier.size(24.dp),
                tint = if (cmd.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(triggerSummary(cmd), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    triggerKindLabel(cmd.kind) + if (cmd.enabled) "" else " · " + stringResource(R.string.trigger_paused),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                cmd.messages.firstOrNull { it.text.isNotBlank() }?.let {
                    Text(it.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (cmd.adminLevel != null) AdminBadge(cmd.adminLevel, prefix = stringResource(R.string.admin_badge_prefix))
        }
    }
}

// -- Teclado fixo -----------------------------------------------------------------------------------

/** Edita o teclado fixo de uma mensagem: linhas de teclas (texto, pedir contato, pedir localização). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReplyKeyboardSheet(keyboard: ReplyKeyboard?, onChange: (ReplyKeyboard?) -> Unit, onDismiss: () -> Unit) {
    var rows by remember { mutableStateOf(keyboard?.rows.orEmpty()) }
    var remove by remember { mutableStateOf(keyboard?.remove == true) }
    fun publish() = onChange(
        rows.map { r -> r.filter { it.text.isNotBlank() } }.filter { it.isNotEmpty() }.let { clean ->
            when {
                clean.isNotEmpty() -> ReplyKeyboard(clean)
                remove -> ReplyKeyboard(remove = true)
                else -> null
            }
        }
    )
    ModalBottomSheet(onDismissRequest = { publish(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.imePadding().padding(start = 16.dp, end = 16.dp, bottom = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.reply_kb_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.reply_kb_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            rows.forEachIndexed { r, row ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.reply_kb_row, r + 1), style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f))
                            IconButton(onClick = { rows = rows.filterIndexed { i, _ -> i != r } }) {
                                AppIcon(R.drawable.ic_delete, description = stringResource(R.string.delete))
                            }
                        }
                        row.forEachIndexed { c, key ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = key.text,
                                    onValueChange = { v ->
                                        rows = rows.mapIndexed { i, rr -> if (i != r) rr else rr.mapIndexed { j, k -> if (j == c) k.copy(text = v.take(64)) else k } }
                                    },
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.reply_kb_key)) },
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = {
                                    rows = rows.mapIndexed { i, rr -> if (i != r) rr else rr.filterIndexed { j, _ -> j != c } }
                                }) { AppIcon(R.drawable.ic_close, description = stringResource(R.string.remove)) }
                            }
                            ChoiceChips(listOf("", ReplyKey.CONTACT, ReplyKey.LOCATION), key.request, { q ->
                                stringResource(
                                    when (q) {
                                        ReplyKey.CONTACT -> R.string.reply_kb_contact
                                        ReplyKey.LOCATION -> R.string.reply_kb_location
                                        else -> R.string.reply_kb_text
                                    }
                                )
                            }) { q ->
                                rows = rows.mapIndexed { i, rr -> if (i != r) rr else rr.mapIndexed { j, k -> if (j == c) k.copy(request = q) else k } }
                            }
                        }
                        if (row.size < 4) TextButton(onClick = {
                            rows = rows.mapIndexed { i, rr -> if (i == r) rr + ReplyKey("") else rr }
                        }) { ButtonLabel(R.drawable.ic_add, stringResource(R.string.reply_kb_add_key)) }
                    }
                }
            }
            if (rows.size < 12) OutlinedButton(onClick = { rows = rows + listOf(listOf(ReplyKey(""))); remove = false }, modifier = Modifier.fillMaxWidth()) {
                ButtonLabel(R.drawable.ic_add, stringResource(R.string.reply_kb_add_row))
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.reply_kb_remove), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.reply_kb_remove_sub), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = remove && rows.isEmpty(), enabled = rows.isEmpty(), onCheckedChange = { remove = it })
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { publish(); onDismiss() }) { Text(stringResource(R.string.done)) }
            }
        }
    }
}

/** Resumo do teclado fixo embaixo da mensagem: [Preços] [Horário] / Remove o teclado. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReplyKeyboardPreview(keyboard: ReplyKeyboard, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            IconLabel(R.drawable.ic_keyboard, stringResource(if (keyboard.remove) R.string.reply_kb_removes else R.string.reply_kb_title),
                style = MaterialTheme.typography.labelMedium)
            keyboard.rows.forEach { row ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { k ->
                        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            when (k.request) {
                                ReplyKey.CONTACT -> AppIcon(R.drawable.ic_contact_page, Modifier.size(14.dp))
                                ReplyKey.LOCATION -> AppIcon(R.drawable.ic_place, Modifier.size(14.dp))
                            }
                            Text(k.text.ifBlank { "…" }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
