package io.github.alerrandrov.easytelebot.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.MediaImporter
import io.github.alerrandrov.easytelebot.data.MessageMedia
import io.github.alerrandrov.easytelebot.data.TokenStore
import io.github.alerrandrov.easytelebot.data.StorageRepository
import io.github.alerrandrov.easytelebot.engine.BotRuntime
import io.github.alerrandrov.easytelebot.service.Alerts
import io.github.alerrandrov.easytelebot.engine.SpamBan
import io.github.alerrandrov.easytelebot.engine.AntispamBridge
import io.github.alerrandrov.easytelebot.engine.ChatMessage
import io.github.alerrandrov.easytelebot.engine.ChatSummary
import io.github.alerrandrov.easytelebot.engine.ChatsBridge
import io.github.alerrandrov.easytelebot.engine.EngineError
import io.github.alerrandrov.easytelebot.engine.ErrorExplainer
import io.github.alerrandrov.easytelebot.engine.PythonBridge
import io.github.alerrandrov.easytelebot.engine.StickerRef
import io.github.alerrandrov.easytelebot.engine.TgEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private const val PAGE = 50

/** Mídia escolhida para enviar: arquivo local ou figurinha já existente no Telegram. */
private data class OutgoingMedia(val media: MessageMedia, val fileId: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(botId: Long, chatId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dir = remember { BotRepository.dir(botId).path }
    val token = remember { TokenStore.load(context, botId) }
    val versions by BotRuntime.chatVersion.collectAsStateWithLifecycle()
    val version = versions[botId]

    val chat by produceState<ChatSummary?>(null, version) {
        value = withContext(Dispatchers.IO) { ChatsBridge.listChats(context, dir).firstOrNull { it.chatId == chatId } }
    }
    val bansVersions by BotRuntime.bansVersion.collectAsStateWithLifecycle()
    val ban by produceState<SpamBan?>(null, bansVersions[botId]) {
        value = withContext(Dispatchers.IO) { AntispamBridge.list(context, dir).firstOrNull { it.userId == chatId } }
    }
    var confirmBlock by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var hasMore by remember { mutableStateOf(true) }
    var loadingOlder by remember { mutableStateOf(false) }

    // Mensagens novas (recebidas ou enviadas): recarrega o fim da conversa e marca como lida.
    LaunchedEffect(version) {
        val latest = withContext(Dispatchers.IO) {
            ChatsBridge.markRead(context, dir, chatId)
            ChatsBridge.messages(context, dir, chatId, null, maxOf(PAGE, messages.size))
        }
        if (messages.isEmpty()) hasMore = latest.size >= PAGE
        messages = latest
    }

    val listState = rememberLazyListState()
    // A lista é invertida (mais nova embaixo): chegar ao fim da lista = topo = carregar mais antigas.
    LaunchedEffect(listState, messages.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (hasMore && !loadingOlder && messages.isNotEmpty() && last >= messages.size - 5) {
                loadingOlder = true
                val older = withContext(Dispatchers.IO) {
                    ChatsBridge.messages(context, dir, chatId, messages.first().messageId, PAGE)
                }
                hasMore = older.size >= PAGE
                messages = older + messages
                loadingOlder = false
            }
        }
    }

    var input by remember { mutableStateOf(TextFieldValue("")) }
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    var attachment by remember { mutableStateOf<OutgoingMedia?>(null) }
    var buttons by remember { mutableStateOf<List<ButtonDraft>>(emptyList()) }
    var showFormat by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showButtons by remember { mutableStateOf(false) }
    var showStickers by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<EngineError?>(null) }
    var menuFor by remember { mutableStateOf<ChatMessage?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var topMenu by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val m = MediaImporter.import(context, botId, uri, folder = "outbox")
        if (m == null) Toast.makeText(context, context.getString(R.string.media_import_failed), Toast.LENGTH_LONG).show()
        else attachment = OutgoingMedia(m)
    }
    val stickerFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        MediaImporter.import(context, botId, uri, folder = "outbox")?.let {
            attachment = OutgoingMedia(it.copy(type = "sticker"))
        }
    }

    fun send() {
        if (token == null || sending) return
        val att = attachment
        if (input.text.isBlank() && att == null) return
        val validButtons = buttons.filter { it.text.isNotBlank() && it.url.isNotBlank() }
        val payload = JSONObject().apply {
            put("text", input.text)
            replyTo?.let { put("reply_to", it.messageId) }
            if (att != null) {
                put("media", JSONObject().apply {
                    put("type", att.media.type)
                    put("name", att.media.name)
                    if (att.fileId != null) put("file_id", att.fileId) else put("file", att.media.file)
                })
            }
            put("buttons", JSONArray().apply {
                toRows(validButtons).forEach { row ->
                    put(JSONArray().apply { row.forEach { put(JSONObject().put("text", it.text).put("url", it.url)) } })
                }
            })
        }
        sending = true
        sendError = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { ChatsBridge.send(context, token, dir, chatId, payload, StorageRepository.engineJson(context, botId)) }
            sending = false
            when (r) {
                is PythonBridge.Result.Ok -> {
                    att?.takeIf { it.fileId == null }?.let { File(it.media.file).delete() }
                    input = TextFieldValue("")
                    replyTo = null
                    attachment = null
                    buttons = emptyList()
                    BotRuntime.bumpChats(botId)
                }
                is PythonBridge.Result.Failed -> sendError = r.error
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                title = {
                    val c = chat
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (c != null) ChatAvatar(c, token, dir, 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(c?.displayName ?: "…", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            val sub = when {
                                c == null -> ""
                                c.isGroup -> stringResource(R.string.chat_group)
                                c.type == "channel" -> stringResource(R.string.chat_channel)
                                c.username.isNotBlank() -> "@${c.username}"
                                else -> "ID ${c.chatId}"
                            }
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    Box {
                        TextButton(onClick = { topMenu = true }) { Text("⋮", fontSize = 20.sp) }
                        DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                            chat?.username?.takeIf { it.isNotBlank() }?.let { u ->
                                DropdownMenuItem(text = { Text(stringResource(R.string.chat_open_telegram)) }, onClick = {
                                    topMenu = false
                                    openUrl(context, "https://t.me/$u")
                                })
                            }
                            if (chat?.type == "private" && ban == null) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.chat_block)) }, onClick = {
                                    topMenu = false
                                    confirmBlock = true
                                })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.chat_clear)) }, onClick = {
                                topMenu = false
                                confirmClear = true
                            })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                input = input,
                onInput = { input = it },
                replyTo = replyTo,
                onCancelReply = { replyTo = null },
                attachment = attachment,
                onAsDocument = { asDoc ->
                    attachment = attachment?.let { a ->
                        val ext = a.media.name.substringAfterLast('.', "").lowercase()
                        val type = if (asDoc) "document"
                        else MediaImporter.typeFor(android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "", ext)
                        a.copy(media = a.media.copy(type = type))
                    }
                },
                onRemoveAttachment = { attachment = null },
                buttonsCount = buttons.count { it.text.isNotBlank() },
                onEditButtons = { showButtons = true },
                showFormat = showFormat,
                showAttachMenu = showAttachMenu,
                onAttachMenu = { showAttachMenu = it },
                onPickMedia = { showAttachMenu = false; picker.launch(arrayOf("image/*", "video/*")) },
                onPickFile = { showAttachMenu = false; picker.launch(arrayOf("*/*")) },
                onPickSticker = { showAttachMenu = false; showStickers = true },
                onToggleFormat = { showAttachMenu = false; showFormat = !showFormat },
                onButtonsMenu = { showAttachMenu = false; showButtons = true },
                sending = sending,
                error = sendError,
                onDismissError = { sendError = null },
                onSend = { send() },
                canSend = token != null,
            )
        },
    ) { padding ->
      Column(Modifier.fillMaxSize().padding(padding)) {
        ban?.let { b ->
            Card(
                Modifier.fillMaxWidth().padding(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.chat_blocked_title), fontWeight = FontWeight.Bold)
                        Text(
                            if (b.reason == "manual") stringResource(R.string.antispam_reason_manual) else b.reasonText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { AntispamBridge.decide(context, token, dir, chatId, AntispamBridge.RELEASE) }
                            Alerts.dismissSpam(context, botId, chatId)
                            BotRuntime.bumpBans(botId)
                        }
                    }) { Text(stringResource(R.string.antispam_release)) }
                }
            }
        }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val reversed = messages.asReversed()
            itemsIndexed(reversed, key = { _, m -> m.messageId }) { i, m ->
                val older = reversed.getOrNull(i + 1)
                Column {
                    if (older == null || !sameDay(older.date, m.date)) DayHeader(m.date)
                    MessageBubble(
                        m = m,
                        showSender = chat?.isGroup == true && !m.outgoing && older?.fromName != m.fromName,
                        token = token,
                        dir = dir,
                        onLongPress = { menuFor = m },
                    )
                    if (menuFor == m) {
                        MessageMenu(
                            m = m,
                            onDismiss = { menuFor = null },
                            onReply = { replyTo = m; menuFor = null },
                            onCopy = {
                                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("msg", m.text))
                                Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
                                menuFor = null
                            },
                            onDelete = {
                                menuFor = null
                                if (token != null) scope.launch {
                                    when (val r = withContext(Dispatchers.IO) { ChatsBridge.delete(context, token, dir, chatId, m.messageId) }) {
                                        is PythonBridge.Result.Ok -> BotRuntime.bumpChats(botId)
                                        is PythonBridge.Result.Failed -> sendError = r.error
                                    }
                                }
                            },
                        )
                    }
                }
            }
            if (loadingOlder) {
                item { Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) } }
            }
            if (!hasMore && messages.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.chat_start_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
        }
      }
    }

    if (showButtons) {
        ButtonsDialog(buttons, onDismiss = { showButtons = false }) { buttons = it; showButtons = false }
    }
    if (showStickers) {
        StickerDialog(
            token = token,
            dir = dir,
            onDismiss = { showStickers = false },
            onPick = { s ->
                showStickers = false
                attachment = OutgoingMedia(MessageMedia("sticker", "", s.emoji.ifBlank { "sticker" }), s.fileId)
            },
            onPickFile = { showStickers = false; stickerFilePicker.launch(arrayOf("image/webp", "application/x-tgsticker", "video/webm")) },
        )
    }
    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text(stringResource(R.string.chat_block_title, chat?.displayName ?: "")) },
            text = { Text(stringResource(R.string.chat_block_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlock = false
                    val c = chat
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            AntispamBridge.block(context, dir, chatId, c?.firstName ?: "", c?.lastName ?: "", c?.username ?: "")
                        }
                        BotRuntime.bumpBans(botId)
                    }
                }) { Text(stringResource(R.string.chat_block), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.chat_clear_title)) },
            text = { Text(stringResource(R.string.chat_clear_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { ChatsBridge.clearChat(context, dir, chatId) }
                        BotRuntime.bumpChats(botId)
                        onBack()
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
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
private fun DayHeader(date: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(date)),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * Cores de um balão. As do bot usam o par primary/onPrimary do tema (contraste
 * garantido pelo Material 3, inclusive com as cores dinâmicas do Android 12+);
 * as recebidas, surfaceContainerHighest/onSurface. Links, citações e código
 * derivam da cor do texto do próprio balão, para nunca sumirem no fundo.
 */
private data class BubbleColors(
    val background: Color,
    val content: Color,
    val secondary: Color,
    val accent: Color,
    val tint: Color,
)

@Composable
private fun bubbleColors(outgoing: Boolean, transparent: Boolean): BubbleColors {
    val cs = MaterialTheme.colorScheme
    return when {
        transparent -> BubbleColors(Color.Transparent, cs.onBackground, cs.onSurfaceVariant, cs.primary, cs.onSurface.copy(alpha = 0.08f))
        outgoing -> BubbleColors(cs.primary, cs.onPrimary, cs.onPrimary.copy(alpha = 0.8f), cs.onPrimary, cs.onPrimary.copy(alpha = 0.18f))
        else -> BubbleColors(cs.surfaceContainerHighest, cs.onSurface, cs.onSurfaceVariant, cs.primary, cs.onSurface.copy(alpha = 0.08f))
    }
}

/** Texto com a formatação do Telegram (entities): negrito, itálico, links, código, spoiler... */
@Composable
fun telegramText(
    text: String,
    entities: List<TgEntity>,
    link: Color = MaterialTheme.colorScheme.primary,
    codeBg: Color = MaterialTheme.colorScheme.surfaceVariant,
    spoiler: Color = MaterialTheme.colorScheme.onSurface,
): AnnotatedString {
    return remember(text, entities, link, codeBg, spoiler) {
        buildAnnotatedString {
            append(text)
            val linkStyle = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))
            entities.forEach { e ->
                val s = e.offset.coerceIn(0, text.length)
                val end = (e.offset + e.length).coerceIn(s, text.length)
                if (s >= end) return@forEach
                val part = text.substring(s, end)
                when (e.type) {
                    "bold" -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), s, end)
                    "italic" -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), s, end)
                    "underline" -> addStyle(SpanStyle(textDecoration = TextDecoration.Underline), s, end)
                    "strikethrough" -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), s, end)
                    "code", "pre" -> addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg), s, end)
                    "spoiler" -> addStyle(SpanStyle(background = spoiler, color = spoiler), s, end)
                    "blockquote", "expandable_blockquote" -> addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = link), s, end)
                    "text_link" -> e.url?.let { addLink(LinkAnnotation.Url(it, linkStyle), s, end) }
                    "url" -> addLink(LinkAnnotation.Url(if (part.contains("://")) part else "https://$part", linkStyle), s, end)
                    "mention" -> addLink(LinkAnnotation.Url("https://t.me/" + part.removePrefix("@"), linkStyle), s, end)
                    "email" -> addLink(LinkAnnotation.Url("mailto:$part", linkStyle), s, end)
                    "phone_number" -> addLink(LinkAnnotation.Url("tel:$part", linkStyle), s, end)
                    "hashtag", "cashtag", "bot_command" -> addStyle(SpanStyle(color = link), s, end)
                }
            }
        }
    }
}

private fun formatDuration(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageBubble(m: ChatMessage, showSender: Boolean, token: String?, dir: String, onLongPress: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }

    fun openMedia() {
        val id = m.fileId ?: return
        if (token == null || opening) return
        opening = true
        scope.launch {
            val ext = m.fileName?.substringAfterLast('.', "") ?: ""
            val path = ChatMedia.file(context, token, dir, id, m.fileUniqueId, ext)
            opening = false
            if (path == null) Toast.makeText(context, context.getString(R.string.chat_download_failed), Toast.LENGTH_LONG).show()
            else if (!ChatMedia.open(context, path, m.mime)) Toast.makeText(context, context.getString(R.string.chat_no_app), Toast.LENGTH_LONG).show()
        }
    }

    if (m.kind == "service") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(m.text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val isSticker = m.kind == "sticker"
    val colors = bubbleColors(m.outgoing, transparent = isSticker)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start) {
      CompositionLocalProvider(LocalContentColor provides colors.content) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.background)
                .combinedClickable(onClick = { if (m.fileId != null && m.kind != "photo") openMedia() }, onLongClick = onLongPress)
                .padding(if (isSticker) 0.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (showSender) {
                Text(m.fromName, style = MaterialTheme.typography.labelLarge, color = colors.accent)
            }
            m.reply?.let { r ->
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(colors.tint).padding(6.dp),
                ) {
                    Box(Modifier.width(3.dp).height(32.dp).background(colors.accent))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            if (r.outgoing) stringResource(R.string.chat_bot_name) else r.fromName,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.accent,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            previewText(r.kind, if (r.kind == "sticker") r.emoji else r.text),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            when (m.kind) {
                "photo" -> {
                    val bmp by rememberTelegramImage(token, dir, m.thumbId ?: m.fileId, null)
                    val ratio = if (m.width > 0 && m.height > 0) m.width.toFloat() / m.height else 1f
                    Box(
                        Modifier.width(260.dp).height((260 / ratio.coerceIn(0.5f, 2f)).dp).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .combinedClickable(onClick = { openMedia() }, onLongClick = onLongPress),
                        contentAlignment = Alignment.Center,
                    ) {
                        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            ?: CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        if (opening) CircularProgressIndicator(Modifier.size(32.dp))
                    }
                }
                "video", "animation", "video_note" -> {
                    val bmp by rememberTelegramImage(token, dir, m.thumbId, null)
                    Box(
                        Modifier.width(240.dp).height(160.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        if (opening) CircularProgressIndicator(Modifier.size(32.dp)) else Text("▶", color = Color.White, fontSize = 36.sp)
                        if (m.duration > 0) {
                            Text(
                                formatDuration(m.duration),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.5f)).padding(4.dp),
                            )
                        }
                    }
                }
                "sticker" -> {
                    if (m.stickerFormat == "webp") {
                        val bmp by rememberTelegramImage(token, dir, m.fileId, m.fileUniqueId, 320)
                        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                                ?: Text(m.emoji.ifBlank { "🖼️" }, fontSize = 64.sp)
                        }
                    } else {
                        // Figurinha animada (.tgs) ou em vídeo (.webm): mostra a prévia ou o emoji.
                        val bmp by rememberTelegramImage(token, dir, m.thumbId, null, 320)
                        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                                ?: Text(m.emoji.ifBlank { "🖼️" }, fontSize = 64.sp)
                        }
                    }
                }
                "document", "audio", "voice" -> {
                    val icon = when (m.kind) { "audio" -> "🎵"; "voice" -> "🎤"; else -> "📄" }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(colors.tint),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (opening) CircularProgressIndicator(Modifier.size(22.dp), color = colors.content, strokeWidth = 2.dp)
                            else Text(icon, fontSize = 20.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                m.fileName ?: previewText(m.kind, ""),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOf(formatSize(m.fileSize), if (m.duration > 0) formatDuration(m.duration) else "")
                                    .filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.secondary,
                            )
                        }
                    }
                }
                "location" -> TextButton(onClick = { openUrl(context, "geo:${m.text}?q=${m.text}") }) {
                    Text("📍 " + stringResource(R.string.media_location) + "\n" + m.text, color = colors.content)
                }
                "contact" -> Text("👤 " + m.text)
                "poll" -> Text("📊 " + m.text, fontWeight = FontWeight.Medium)
                "other" -> Text(stringResource(R.string.chat_unsupported), fontStyle = FontStyle.Italic)
            }

            if (m.text.isNotEmpty() && m.kind !in listOf("location", "contact", "poll", "other")) {
                Text(
                    telegramText(m.text, m.entities, link = colors.accent, codeBg = colors.tint, spoiler = colors.content),
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (m.deleted) TextDecoration.LineThrough else null,
                    modifier = Modifier.padding(horizontal = if (isSticker) 8.dp else 0.dp),
                )
            }

            val footer = listOfNotNull(
                when (m.source) {
                    "auto" -> stringResource(R.string.chat_source_auto)
                    "manual" -> stringResource(R.string.chat_source_manual)
                    else -> null
                },
                if (m.edited) stringResource(R.string.chat_edited) else null,
                if (m.deleted) stringResource(R.string.chat_deleted) else null,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.date)),
            ).joinToString(" · ")
            Text(
                footer,
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondary,
                modifier = Modifier.align(Alignment.End).padding(horizontal = if (isSticker) 8.dp else 0.dp),
            )
        }
      }

        // Botões inline embaixo da mensagem.
        m.buttons.forEach { row ->
            Row(Modifier.widthIn(max = 300.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { b ->
                    OutlinedButton(
                        onClick = { b.url?.let { openUrl(context, it) } },
                        enabled = b.url != null,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text(b.text + if (b.url != null) " ↗" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

@Composable
private fun MessageMenu(m: ChatMessage, onDismiss: () -> Unit, onReply: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.outgoing) Alignment.TopEnd else Alignment.TopStart) {
        DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
            if (!m.deleted) DropdownMenuItem(text = { Text(stringResource(R.string.chat_reply)) }, onClick = onReply)
            if (m.text.isNotBlank()) DropdownMenuItem(text = { Text(stringResource(R.string.chat_copy)) }, onClick = onCopy)
            if (!m.deleted) DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_delete_for_all), color = MaterialTheme.colorScheme.error) },
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun Composer(
    input: TextFieldValue,
    onInput: (TextFieldValue) -> Unit,
    replyTo: ChatMessage?,
    onCancelReply: () -> Unit,
    attachment: OutgoingMedia?,
    onAsDocument: (Boolean) -> Unit,
    onRemoveAttachment: () -> Unit,
    buttonsCount: Int,
    onEditButtons: () -> Unit,
    showFormat: Boolean,
    showAttachMenu: Boolean,
    onAttachMenu: (Boolean) -> Unit,
    onPickMedia: () -> Unit,
    onPickFile: () -> Unit,
    onPickSticker: () -> Unit,
    onToggleFormat: () -> Unit,
    onButtonsMenu: () -> Unit,
    sending: Boolean,
    error: EngineError?,
    onDismissError: () -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
) {
    val context = LocalContext.current
    Surface(tonalElevation = 3.dp, modifier = Modifier.navigationBarsPadding().imePadding()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            error?.let { e ->
                ExplanationCard(ErrorExplainer.explain(context, e)) {
                    TextButton(onClick = onDismissError) { Text(stringResource(R.string.dismiss)) }
                }
            }
            replyTo?.let { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(32.dp).background(MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.chat_replying_to, if (r.outgoing) stringResource(R.string.chat_bot_name) else r.fromName),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(previewText(r.kind, if (r.kind == "sticker") r.emoji else r.text), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onCancelReply) { Text("✕") }
                }
            }
            attachment?.let { a ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(previewText(a.media.type, ""), style = MaterialTheme.typography.labelLarge)
                            Text(a.media.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (a.fileId == null && a.media.type != "sticker") {
                            Checkbox(checked = a.media.type == "document", onCheckedChange = onAsDocument)
                            Text(stringResource(R.string.chat_as_file), style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = onRemoveAttachment) { Text("✕") }
                    }
                }
            }
            if (buttonsCount > 0) {
                TextButton(onClick = onEditButtons) {
                    Text("🔗 " + LocalContext.current.resources.getQuantityString(R.plurals.cmd_buttons_count, buttonsCount, buttonsCount))
                }
            }
            if (showFormat) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    FormatButton("B", fontWeight = FontWeight.Bold) { onInput(wrap(input, "*", "*")) }
                    FormatButton("I", fontStyle = FontStyle.Italic) { onInput(wrap(input, "_", "_")) }
                    FormatButton("U", decoration = TextDecoration.Underline) { onInput(wrap(input, "__", "__")) }
                    FormatButton("S", decoration = TextDecoration.LineThrough) { onInput(wrap(input, "~", "~")) }
                    FormatButton("</>", mono = true) { onInput(wrap(input, "`", "`")) }
                    FormatButton(stringResource(R.string.fmt_spoiler)) { onInput(wrap(input, "||", "||")) }
                    FormatButton(stringResource(R.string.fmt_link)) {
                        val v = wrap(input, "[", "](https://)")
                        onInput(v.copy(selection = androidx.compose.ui.text.TextRange(v.selection.start - 1)))
                    }
                    FormatButton(stringResource(R.string.fmt_quote)) { onInput(quote(input)) }
                    FormatButton("{nome}") { onInput(insert(input, "{nome}")) }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { onAttachMenu(true) }) { Text("📎", fontSize = 22.sp) }
                    DropdownMenu(expanded = showAttachMenu, onDismissRequest = { onAttachMenu(false) }) {
                        DropdownMenuItem(text = { Text("🖼️  " + stringResource(R.string.chat_attach_media)) }, onClick = onPickMedia)
                        DropdownMenuItem(text = { Text("📄  " + stringResource(R.string.chat_attach_file)) }, onClick = onPickFile)
                        DropdownMenuItem(text = { Text("😀  " + stringResource(R.string.chat_attach_sticker)) }, onClick = onPickSticker)
                        DropdownMenuItem(text = { Text("🔗  " + stringResource(R.string.btn_title)) }, onClick = onButtonsMenu)
                        DropdownMenuItem(text = { Text("Aa  " + stringResource(R.string.chat_formatting)) }, onClick = onToggleFormat)
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = onInput,
                    placeholder = {
                        Text(stringResource(if (attachment != null) R.string.cmd_caption else R.string.chat_type_message))
                    },
                    maxLines = 6,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                )
                Spacer(Modifier.width(6.dp))
                FilledIconButton(
                    onClick = onSend,
                    enabled = canSend && !sending && (input.text.isNotBlank() || attachment != null),
                    modifier = Modifier.size(48.dp),
                ) {
                    if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(painterResource(R.drawable.ic_send), stringResource(R.string.chat_send))
                }
            }
        }
    }
}

@Composable
private fun ButtonsDialog(initial: List<ButtonDraft>, onDismiss: () -> Unit, onDone: (List<ButtonDraft>) -> Unit) {
    var list by remember { mutableStateOf(initial.ifEmpty { listOf(ButtonDraft("", "", false)) }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.btn_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.forEachIndexed { i, b ->
                    ButtonEditor(
                        button = b,
                        first = i == 0,
                        onChange = { nb -> list = list.mapIndexed { j, x -> if (j == i) nb else x } },
                        onRemove = { list = list.filterIndexed { j, _ -> j != i } },
                    )
                }
                TextButton(onClick = { list = list + ButtonDraft("", "", false) }) { Text("+  " + stringResource(R.string.btn_add)) }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(list.filter { it.text.isNotBlank() || it.url.isNotBlank() }) }) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StickerDialog(token: String?, dir: String, onDismiss: () -> Unit, onPick: (StickerRef) -> Unit, onPickFile: () -> Unit) {
    val context = LocalContext.current
    val stickers by produceState<List<StickerRef>?>(null) {
        value = withContext(Dispatchers.IO) { ChatsBridge.stickers(context, dir) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_attach_sticker)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val list = stickers
                when {
                    list == null -> CircularProgressIndicator()
                    list.isEmpty() -> Text(stringResource(R.string.chat_no_stickers))
                    else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.forEach { s ->
                            val bmp by rememberTelegramImage(token, dir, if (s.format == "webp") s.fileId else s.thumbId,
                                if (s.format == "webp") s.uniqueId else null, 160)
                            Box(Modifier.size(64.dp).clickable { onPick(s) }, contentAlignment = Alignment.Center) {
                                bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                                    ?: Text(s.emoji.ifBlank { "🖼️" }, fontSize = 32.sp)
                            }
                        }
                    }
                }
                Text(stringResource(R.string.chat_stickers_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onPickFile) { Text(stringResource(R.string.chat_sticker_file)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
