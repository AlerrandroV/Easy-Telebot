package io.github.alerrandrov.easytelebot.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import io.github.alerrandrov.easytelebot.engine.ChatsBridge
import io.github.alerrandrov.easytelebot.engine.PythonBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Imagens das conversas: baixa sob demanda, reduz para caber na tela e guarda em memória. */
object ChatMedia {
    private val bitmaps = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val paths = HashMap<String, String>()
    private val mutex = Mutex()

    /** Caminho local do arquivo (baixa na primeira vez). `null` se falhar. */
    suspend fun file(context: Context, token: String, dir: String, fileId: String, uniqueId: String?, ext: String = ""): String? {
        val key = uniqueId ?: fileId
        paths[key]?.let { if (File(it).exists()) return it }
        return mutex.withLock {
            paths[key]?.let { if (File(it).exists()) return@withLock it }
            when (val r = withContext(Dispatchers.IO) { ChatsBridge.download(context, token, dir, fileId, uniqueId, ext) }) {
                is PythonBridge.Result.Ok -> r.value.optString("path").also { paths[key] = it }
                is PythonBridge.Result.Failed -> null
            }
        }
    }

    suspend fun bitmap(path: String, maxSize: Int = 720): Bitmap? {
        bitmaps.get(path)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxSize || bounds.outHeight / (sample * 2) >= maxSize) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.also { bitmaps.put(path, it) }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Abre o arquivo em outro app (player de vídeo, leitor de PDF...). */
    fun open(context: Context, path: String, mime: String?): Boolean {
        val file = File(path)
        val type = mime?.takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "*/*"
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return safeStart(context, Intent.createChooser(intent, null))
    }
}

/** Bitmap de uma mídia do Telegram (thumb ou arquivo), carregado em segundo plano. */
@Composable
fun rememberTelegramImage(token: String?, dir: String, fileId: String?, uniqueId: String?, maxSize: Int = 720): State<Bitmap?> {
    val context = LocalContext.current
    return produceState<Bitmap?>(null, fileId) {
        if (token == null || fileId == null) return@produceState
        val path = ChatMedia.file(context, token, dir, fileId, uniqueId) ?: return@produceState
        value = ChatMedia.bitmap(path, maxSize)
    }
}

/** Foto de um arquivo local qualquer (ex.: avatar já baixado). */
@Composable
fun rememberLocalImage(path: String?, maxSize: Int = 256): State<Bitmap?> = produceState<Bitmap?>(null, path) {
    value = path?.let { ChatMedia.bitmap(it, maxSize) }
}
