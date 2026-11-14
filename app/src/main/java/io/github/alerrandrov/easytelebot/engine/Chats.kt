package io.github.alerrandrov.easytelebot.engine

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject

data class ChatSummary(
    val chatId: Long,
    val type: String,
    val title: String,
    val firstName: String,
    val lastName: String,
    val username: String,
    val lastAt: Long,
    val lastKind: String,
    val lastText: String,
    val lastOutgoing: Boolean,
    val unread: Int,
) {
    val isGroup get() = type == "group" || type == "supergroup"
    val displayName: String
        get() = title.ifBlank { listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ") }
            .ifBlank { username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: chatId.toString() }
}

data class TgEntity(val type: String, val offset: Int, val length: Int, val url: String?)

data class TgButton(val text: String, val url: String?)

data class ReplyRef(val outgoing: Boolean, val fromName: String, val kind: String, val text: String, val emoji: String)

data class ChatMessage(
    val chatId: Long,
    val messageId: Long,
    val outgoing: Boolean,
    /** "user" (recebida), "auto" (resposta de comando) ou "manual" (enviada pelo app). */
    val source: String,
    val fromName: String,
    val date: Long,
    val edited: Boolean,
    val deleted: Boolean,
    /** text, photo, video, animation, video_note, sticker, voice, audio, document, location, contact, poll, service, other */
    val kind: String,
    val text: String,
    val entities: List<TgEntity>,
    val fileId: String?,
    val fileUniqueId: String?,
    val thumbId: String?,
    val fileName: String?,
    val mime: String?,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val duration: Int,
    val emoji: String,
    val stickerFormat: String,
    val buttons: List<List<TgButton>>,
    val reply: ReplyRef?,
)

data class StickerRef(val fileId: String, val uniqueId: String, val thumbId: String?, val emoji: String, val format: String)

/** Ponte para `chats.py` (conversas em SQLite). Tudo bloqueia: chame fora da thread principal. */
object ChatsBridge {
    private fun module(context: Context) = run {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        Python.getInstance().getModule("telebot_engine.chats")
    }

    private fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key)
    private fun JSONObject.strOrNull(key: String) = if (isNull(key)) null else optString(key).ifEmpty { null }

    fun listChats(context: Context, dir: String): List<ChatSummary> {
        val arr = JSONArray(module(context).callAttr("list_chats", dir).toString())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ChatSummary(
                chatId = o.getLong("chat_id"),
                type = o.str("type"),
                title = o.str("title"),
                firstName = o.str("first_name"),
                lastName = o.str("last_name"),
                username = o.str("username"),
                lastAt = o.optLong("last_at") * 1000,
                lastKind = o.str("last_kind"),
                lastText = o.str("last_text"),
                lastOutgoing = o.optInt("last_outgoing") == 1,
                unread = o.optInt("unread"),
            )
        }
    }

    fun unreadTotal(context: Context, dir: String): Int =
        module(context).callAttr("unread_total", dir).toInt()

    fun messages(context: Context, dir: String, chatId: Long, before: Long? = null, limit: Int = 50): List<ChatMessage> {
        val arr = JSONArray(module(context).callAttr("get_messages", dir, chatId, before, limit).toString())
        return (0 until arr.length()).map { i -> parseMessage(arr.getJSONObject(i)) }
    }

    private fun parseMessage(o: JSONObject): ChatMessage {
        val ents = o.optJSONArray("entities") ?: JSONArray()
        val rows = o.optJSONArray("buttons") ?: JSONArray()
        val reply = o.optJSONObject("reply")
        return ChatMessage(
            chatId = o.getLong("chat_id"),
            messageId = o.getLong("message_id"),
            outgoing = o.optInt("outgoing") == 1,
            source = o.str("source"),
            fromName = o.str("from_name"),
            date = o.optLong("date") * 1000,
            edited = o.optInt("edited") == 1,
            deleted = o.optInt("deleted") == 1,
            kind = o.str("kind").ifEmpty { "text" },
            text = o.str("text"),
            entities = (0 until ents.length()).map { j ->
                val e = ents.getJSONObject(j)
                TgEntity(e.optString("type"), e.optInt("offset"), e.optInt("length"), e.strOrNull("url"))
            },
            fileId = o.strOrNull("file_id"),
            fileUniqueId = o.strOrNull("file_unique_id"),
            thumbId = o.strOrNull("thumb_id"),
            fileName = o.strOrNull("file_name"),
            mime = o.strOrNull("mime"),
            fileSize = o.optLong("file_size"),
            width = o.optInt("width"),
            height = o.optInt("height"),
            duration = o.optInt("duration"),
            emoji = o.str("emoji"),
            stickerFormat = o.str("sticker_format"),
            buttons = (0 until rows.length()).map { r ->
                val row = rows.getJSONArray(r)
                (0 until row.length()).map { c ->
                    val b = row.getJSONObject(c)
                    TgButton(b.optString("text"), b.strOrNull("url"))
                }
            },
            reply = reply?.let {
                ReplyRef(it.optInt("outgoing") == 1, it.str("from_name"), it.str("kind"), it.str("text"), it.str("emoji"))
            },
        )
    }

    fun markRead(context: Context, dir: String, chatId: Long) {
        module(context).callAttr("mark_read", dir, chatId)
    }

    /** Fecha o banco de conversas (antes de apagar a pasta do bot). */
    fun close(context: Context, dir: String) {
        runCatching { module(context).callAttr("close", dir) }
    }

    fun clearChat(context: Context, dir: String, chatId: Long) {
        module(context).callAttr("clear_chat", dir, chatId)
    }

    fun stickers(context: Context, dir: String): List<StickerRef> {
        val arr = JSONArray(module(context).callAttr("stickers", dir).toString())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            StickerRef(o.getString("file_id"), o.str("file_unique_id"), o.strOrNull("thumb_id"), o.str("emoji"), o.str("sticker_format"))
        }
    }

    private fun result(raw: String): PythonBridge.Result<JSONObject> {
        val json = JSONObject(raw)
        return if (json.optBoolean("ok")) PythonBridge.Result.Ok(json) else PythonBridge.Result.Failed(EngineError.fromJson(json.getJSONObject("error")))
    }

    private inline fun guard(block: () -> PythonBridge.Result<JSONObject>): PythonBridge.Result<JSONObject> = try {
        block()
    } catch (e: Exception) {
        PythonBridge.Result.Failed(EngineError("unknown", e.message ?: e.toString()))
    }

    /** Baixa (ou reaproveita do cache) um arquivo do Telegram. Devolve o caminho local. */
    fun download(context: Context, token: String, dir: String, fileId: String, uniqueId: String?, ext: String = "") =
        guard { result(module(context).callAttr("download", token, dir, fileId, uniqueId ?: fileId, ext).toString()) }

    fun chatPhoto(context: Context, token: String, dir: String, chatId: Long): String? = try {
        JSONObject(module(context).callAttr("chat_photo", token, dir, chatId).toString())
            .let { if (it.isNull("path")) null else it.optString("path") }
    } catch (e: Exception) {
        null
    }

    fun send(context: Context, token: String, dir: String, chatId: Long, payload: JSONObject) =
        guard { result(module(context).callAttr("send", token, dir, chatId, payload.toString()).toString()) }

    fun delete(context: Context, token: String, dir: String, chatId: Long, messageId: Long) =
        guard { result(module(context).callAttr("delete_message", token, dir, chatId, messageId).toString()) }
}
