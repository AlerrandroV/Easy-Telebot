package io.github.alerrandrov.easytelebot.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object MediaImporter {
    /** Copia um arquivo escolhido pela pessoa para `bots/<id>/media/` e diz como enviá-lo. */
    fun import(context: Context, botId: Long, uri: Uri, folder: String = "media"): MessageMedia? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: ""
        val name = displayName(context, uri) ?: "arquivo"
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && it.length <= 5 }
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        val dir = File(BotRepository.dir(botId), folder).apply { mkdirs() }
        val fileName = "${UUID.randomUUID()}.$ext"
        val target = File(dir, fileName)
        try {
            resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                ?: return null
        } catch (e: Exception) {
            target.delete()
            return null
        }
        val path = if (folder == "media") "media/$fileName" else target.absolutePath
        return MessageMedia(typeFor(mime, ext), path, name)
    }

    fun typeFor(mime: String, ext: String): String = when {
        mime == "image/gif" || ext == "gif" -> "animation"
        mime.startsWith("image/") && ext != "webp" -> "photo"
        mime.startsWith("video/") -> "video"
        mime == "audio/ogg" || ext == "ogg" || ext == "oga" -> "voice"
        mime.startsWith("audio/") -> "audio"
        else -> "document"
    }

    /** Copia uma imagem escolhida para um arquivo temporário (para trocar a foto do bot). */
    fun toTemp(context: Context, uri: Uri): File? {
        val f = File(context.cacheDir, "upload_${System.currentTimeMillis()}.jpg")
        return try {
            context.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } } ?: return null
            f
        } catch (e: Exception) {
            null
        }
    }

    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * "Código aberto": gera um .zip com o motor (o mesmo do app), o `run.py`, o
 * `config.json` e as mídias do bot, para rodar em qualquer computador com Python.
 */
object BotExporter {
    private const val ASSET_ROOT = "export"

    fun suggestedName(meta: BotMeta) =
        "${meta.username.ifEmpty { "bot" }}-easytelebot.zip"

    fun export(context: Context, meta: BotMeta, target: Uri, includeToken: Boolean): Boolean {
        val folder = meta.username.ifEmpty { "bot_${meta.id}" }
        return try {
            val out = context.contentResolver.openOutputStream(target) ?: return false
            ZipOutputStream(out).use { zip ->
                fun put(path: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry("$folder/$path"))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                copyAssets(context, ASSET_ROOT, "") { path, bytes -> put(path, bytes) }
                put("config.json", ConfigRepository.load(meta.id).toJson().toString(2).toByteArray())
                BotRepository.mediaDir(meta.id).listFiles()?.forEach { put("media/${it.name}", it.readBytes()) }
                listOf("admins.json", "bans.json").forEach { name ->
                    java.io.File(BotRepository.dir(meta.id), name).takeIf { it.exists() }?.let { put(name, it.readBytes()) }
                }
                put("storage.json", StorageRepository.load(meta.id).toJson().toString(2).toByteArray())
                if (includeToken) {
                    TokenStore.load(context, meta.id)?.let { put("token.txt", (it + "\n").toByteArray()) }
                    put("storage_secrets.json", StorageRepository.loadSecrets(context, meta.id).toJson().toString(2).toByteArray())
                    put("payments.json", PaymentRepository.load(context, meta.id).toJson().toString(2).toByteArray())
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun copyAssets(context: Context, assetPath: String, rel: String, put: (String, ByteArray) -> Unit) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            context.assets.open(assetPath).use { put(rel, it.readBytes()) }
            return
        }
        children.forEach { child ->
            copyAssets(context, "$assetPath/$child", if (rel.isEmpty()) child else "$rel/$child", put)
        }
    }
}
