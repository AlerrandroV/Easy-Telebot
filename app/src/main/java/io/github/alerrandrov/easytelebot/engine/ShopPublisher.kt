package io.github.alerrandrov.easytelebot.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.ConfigRepository
import io.github.alerrandrov.easytelebot.data.PaymentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Publica a vitrine: gera as miniaturas das fotos dos produtos, manda para o imgbb
 * (se houver chave; senão embute em base64) e envia o catalogo.json para o Gist do
 * dono do bot. O bot passa a abrir a vitrine com o link desse JSON.
 */
object ShopPublisher {
    private const val THUMB_SIZE = 480
    private const val THUMB_QUALITY = 72
    private const val CACHE_FILE = "shop_images.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    data class Published(val products: Int, val bytes: Int)

    private fun title(botId: Long): String {
        val meta = BotRepository.bots.value.firstOrNull { it.id == botId }
        return meta?.name?.ifBlank { null } ?: meta?.username ?: "Loja"
    }

    /** A vitrine publicada não mostra os produtos como estão agora. */
    fun isOutdated(botId: Long): Boolean {
        val shop = ConfigRepository.load(botId).shop
        return shop.catalogUrl.isNotBlank() && shop.publishedHash != shop.storefrontHash(title(botId))
    }

    /** Há credencial para publicar (token do GitHub). */
    fun canPublish(context: Context, botId: Long) = PaymentRepository.load(context, botId).githubToken.isNotBlank()

    /** Republica em segundo plano depois de mudar produtos (se já estiver configurado). */
    fun publishInBackground(context: Context, botId: Long) {
        val app = context.applicationContext
        if (!canPublish(app, botId)) return
        scope.launch {
            when (val r = publish(app, botId)) {
                is PythonBridge.Result.Ok ->
                    BotRuntime.add(botId, LogEntry.Level.INFO, app.getString(R.string.log_storefront_published, r.value.products))
                is PythonBridge.Result.Failed -> BotRuntime.add(
                    botId, LogEntry.Level.ERROR,
                    app.getString(R.string.log_storefront_failed) + ": " + ErrorExplainer.explain(app, r.error).title, r.error,
                )
            }
            BotRuntime.bumpShop(botId)
        }
    }

    /** Publica agora (bloqueia: chame fora da thread principal). */
    suspend fun publish(context: Context, botId: Long): PythonBridge.Result<Published> = lock.withLock {
        try {
            val secrets = PaymentRepository.load(context, botId)
            if (secrets.githubToken.isBlank()) {
                return@withLock PythonBridge.Result.Failed(EngineError("host_config", context.getString(R.string.shop_host_missing_token)))
            }
            val config = ConfigRepository.load(botId)
            val title = title(botId)
            val dir = BotRepository.dir(botId)

            // Fotos: link do imgbb (guardado por arquivo, para não reenviar) ou miniatura embutida.
            val cacheFile = File(dir, CACHE_FILE)
            val cache = try { JSONObject(cacheFile.readText()) } catch (e: Exception) { JSONObject() }
            val images = JSONObject()
            for (p in config.shop.products.filter { it.active }) {
                val photo = p.photo ?: continue
                val file = File(dir, photo)
                if (!file.exists()) continue
                val cached = cache.optString(photo)
                if (secrets.imgbbKey.isNotBlank() && cached.startsWith("https://")) {
                    images.put(p.id, cached)
                    continue
                }
                val thumb = thumbnail(file) ?: continue
                val b64 = Base64.encodeToString(thumb, Base64.NO_WRAP)
                if (secrets.imgbbKey.isNotBlank()) {
                    val o = JSONObject(PythonBridge.module(context, "shop.hosting").callAttr("app_upload_image", secrets.imgbbKey, b64).toString())
                    if (!o.optBoolean("ok")) return@withLock PythonBridge.Result.Failed(EngineError.fromJson(o.getJSONObject("error")))
                    val url = o.getString("url")
                    cache.put(photo, url)
                    images.put(p.id, url)
                } else {
                    images.put(p.id, "data:image/jpeg;base64,$b64")
                }
            }
            if (secrets.imgbbKey.isNotBlank()) cacheFile.writeText(cache.toString())

            val o = JSONObject(
                PythonBridge.module(context, "shop.hosting").callAttr(
                    "app_publish", BotRepository.configFile(botId).absolutePath, title, images.toString(),
                    secrets.githubToken, config.shop.gistId,
                ).toString(),
            )
            if (!o.optBoolean("ok")) return@withLock PythonBridge.Result.Failed(EngineError.fromJson(o.getJSONObject("error")))

            // Grava o link publicado (relendo a config: pode ter mudado enquanto publicava).
            val current = ConfigRepository.load(botId)
            ConfigRepository.save(
                botId,
                current.copy(
                    shop = current.shop.copy(
                        catalogUrl = o.getString("raw_url"),
                        gistId = o.optString("gist_id"),
                        publishedHash = config.shop.storefrontHash(title),
                    ),
                ),
            )
            PythonBridge.Result.Ok(Published(o.optInt("products"), o.optInt("bytes")))
        } catch (e: Exception) {
            PythonBridge.Result.Failed(EngineError("host_unknown", e.message ?: e.toString()))
        }
    }

    /** Volta a mandar o catálogo dentro do próprio link (sem Gist). */
    fun unpublish(botId: Long) {
        val current = ConfigRepository.load(botId)
        ConfigRepository.save(botId, current.copy(shop = current.shop.copy(catalogUrl = "", publishedHash = "")))
    }

    /** Miniatura JPEG de no máximo [THUMB_SIZE] px (cerca de 20 a 40 KB). */
    private fun thumbnail(file: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_SIZE && bounds.outHeight / (sample * 2) >= THUMB_SIZE) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val degrees = try {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        val scale = minOf(1f, THUMB_SIZE.toFloat() / maxOf(bmp.width, bmp.height))
        val matrix = Matrix().apply { postScale(scale, scale); postRotate(degrees) }
        val scaled = if (scale < 1f || degrees != 0f) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true) else bmp
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
            out.toByteArray()
        }
    }
}
