package io.github.alerrandrov.easytelebot.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.TokenStore
import io.github.alerrandrov.easytelebot.engine.BotRuntime
import io.github.alerrandrov.easytelebot.engine.ChatSummary
import io.github.alerrandrov.easytelebot.engine.ChatsBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
fun ChatsScreen(botId: Long, onOpenChat: (Long) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val dir = remember { BotRepository.dir(botId).path }
    val token = remember { TokenStore.load(context, botId) }
    val versions by BotRuntime.chatVersion.collectAsStateWithLifecycle()
    val chats by produceState<List<ChatSummary>?>(null, versions[botId]) {
        value = withContext(Dispatchers.IO) { ChatsBridge.listChats(context, dir) }
    }
    var query by remember { mutableStateOf("") }

    Scaffold(topBar = { AppBar(stringResource(R.string.chats_title), onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.chats_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                )
            }
            val list = chats
            if (list != null && list.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.chats_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            val filtered = list.orEmpty().filter {
                query.isBlank() || it.displayName.contains(query, true) || it.username.contains(query.removePrefix("@"), true)
            }
            items(filtered, key = { it.chatId }) { chat ->
                ChatRow(chat, token, dir) { onOpenChat(chat.chatId) }
                HorizontalDivider(Modifier.padding(start = 76.dp))
            }
            if (!list.isNullOrEmpty()) {
                item {
                    Text(
                        stringResource(R.string.chats_history_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatRow(chat: ChatSummary, token: String?, dir: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatAvatar(chat, token, dir, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chat.isGroup || chat.type == "channel") {
                    AppIcon(
                        if (chat.isGroup) R.drawable.ic_group else R.drawable.ic_campaign,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    chat.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (chat.unread > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    shortTime(chat.lastAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (chat.unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PreviewLine(
                    chat.lastKind,
                    chat.lastText,
                    Modifier.weight(1f),
                    prefix = if (chat.lastOutgoing) stringResource(R.string.chat_you_prefix) else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (chat.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 1.dp),
                    ) {
                        Text(
                            if (chat.unread > 99) "99+" else chat.unread.toString(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatAvatar(chat: ChatSummary, token: String?, dir: String, size: Dp) {
    val context = LocalContext.current
    val path by produceState<String?>(null, chat.chatId) {
        if (token != null) value = withContext(Dispatchers.IO) { ChatsBridge.chatPhoto(context, token, dir, chat.chatId) }
    }
    val bitmap by rememberLocalImage(path)
    val bmp = bitmap
    if (bmp != null) {
        Image(bmp.asImageBitmap(), null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        Box(
            Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                chat.displayName.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** Texto curto para a lista: "Foto", "Vídeo · legenda", o texto da mensagem... (o ícone vem de [mediaIcon]). */
@Composable
fun previewText(kind: String, text: String): String {
    val label = when (kind) {
        "photo" -> stringResource(R.string.media_photo)
        "video" -> stringResource(R.string.media_video)
        "animation" -> stringResource(R.string.media_animation)
        "video_note" -> stringResource(R.string.media_video_note)
        "voice" -> stringResource(R.string.media_voice)
        "audio" -> stringResource(R.string.media_audio)
        "document" -> stringResource(R.string.media_document)
        "sticker" -> return listOf(text, stringResource(R.string.media_sticker)).filter { it.isNotBlank() }.joinToString(" ")
        "location" -> return stringResource(R.string.media_location)
        "contact" -> return text.substringBefore("\n")
        "poll" -> return text
        else -> null
    }
    return when {
        label == null -> text
        text.isBlank() -> label
        else -> "$label · $text"
    }
}

/** Ícone do tipo de mensagem (foto, vídeo, áudio...), ou `null` para texto. */
fun mediaIcon(kind: String): Int? = when (kind) {
    "photo" -> R.drawable.ic_image
    "video", "animation", "video_note" -> R.drawable.ic_movie
    "voice" -> R.drawable.ic_mic
    "audio" -> R.drawable.ic_music_note
    "document" -> R.drawable.ic_description
    "sticker" -> R.drawable.ic_emoji_emotions
    "location" -> R.drawable.ic_place
    "contact" -> R.drawable.ic_contact_page
    "poll" -> R.drawable.ic_poll
    else -> null
}

/** Prévia de uma mensagem numa linha: ícone do tipo + texto. */
@Composable
fun PreviewLine(
    kind: String,
    text: String,
    modifier: Modifier = Modifier,
    prefix: String = "",
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        mediaIcon(kind)?.let {
            if (prefix.isNotEmpty()) Text(prefix, style = style, color = color, maxLines = 1)
            AppIcon(it, Modifier.size(16.dp), tint = if (color == Color.Unspecified) LocalContentColor.current else color)
            Spacer(Modifier.width(4.dp))
            Text(previewText(kind, text), style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } ?: Text(prefix + previewText(kind, text), style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun shortTime(millis: Long): String {
    if (millis <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
    else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(millis))
}
