package io.github.alerrandrov.easytelebot.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Produto do catálogo. Preço em centavos. Mesmo formato do `shop/catalog.py`. */
data class Product(
    val id: String = UUID.randomUUID().toString().take(12),
    val name: String = "",
    val description: String = "",
    val price: Long = 0,
    /** Preço em Telegram Stars (opcional). */
    val stars: Int? = null,
    /** "digital" ou "subscription". */
    val type: String = TYPE_DIGITAL,
    val active: Boolean = true,
    /** Unidades à venda no total; -1 = ilimitado. */
    val stock: Int = -1,
    /** Foto mostrada no chat (arquivo em media/). */
    val photo: String? = null,
    /** Imagem da vitrine (link público), opcional. */
    val imageUrl: String = "",
    val chatId: Long? = null,
    val chatTitle: String = "",
    val days: Int = 30,
    /** O que a pessoa recebe depois de pagar (produto digital). */
    val delivery: BotMessage? = null,
) {
    val isSubscription get() = type == TYPE_SUBSCRIPTION

    companion object {
        const val TYPE_DIGITAL = "digital"
        const val TYPE_SUBSCRIPTION = "subscription"
    }
}

data class ShopGateways(
    val mercadoPago: Boolean = false,
    /** Pix com QR code no chat (precisa de um e-mail de pagador). */
    val pix: Boolean = false,
    val payerEmail: String = "",
    val blink: Boolean = false,
    val stars: Boolean = false,
)

data class ShopSettings(
    val enabled: Boolean = false,
    val currency: String = "BRL",
    val intro: String = "Confira nossos produtos:",
    val miniapp: Boolean = true,
    val storeUrl: String = DEFAULT_STORE_URL,
    /** Classe mínima de admin avisada a cada venda. */
    val notifyLevel: Int = 1,
    val remindDays: Int = 3,
    val products: List<Product> = emptyList(),
    val gateways: ShopGateways = ShopGateways(),
    /** Link do catalogo.json publicado (Gist, revisão exata). Vazio = catálogo vai no próprio link. */
    val catalogUrl: String = "",
    val gistId: String = "",
    /** Assinatura do que foi publicado, para saber se a vitrine está desatualizada. */
    val publishedHash: String = "",
) {
    /** Assinatura do conteúdo que aparece na vitrine (produtos, moeda). */
    fun storefrontHash(title: String): String =
        (title + currency + JSONArray().apply {
            products.forEach { p ->
                put(JSONArray(listOf(p.id, p.name, p.description, p.price, p.type, p.days, p.active, p.stock, p.photo ?: "", p.imageUrl)))
            }
        }.toString()).hashCode().toString(16)

    fun mediaFiles(): Set<String> =
        products.flatMap { listOfNotNull(it.photo, it.delivery?.media?.file) }.toSet()

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("currency", currency)
        .put("triggers", JSONArray(TRIGGERS))
        .put("intro", intro)
        .put("miniapp", miniapp)
        .put("store_url", storeUrl)
        .put("catalog_url", catalogUrl)
        .put("gist_id", gistId)
        .put("published_hash", publishedHash)
        .put("notify_level", notifyLevel)
        .put("remind_days", remindDays)
        .put("products", JSONArray().apply {
            products.forEach { p ->
                put(JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("description", p.description)
                    .put("price", p.price)
                    .put("stars", p.stars ?: JSONObject.NULL)
                    .put("type", p.type)
                    .put("active", p.active)
                    .put("stock", p.stock)
                    .put("photo", p.photo ?: JSONObject.NULL)
                    .put("image_url", p.imageUrl)
                    .put("chat_id", p.chatId ?: JSONObject.NULL)
                    .put("chat_title", p.chatTitle)
                    .put("days", p.days)
                    .put("delivery", p.delivery?.let { BotConfig.messageJson(it) } ?: JSONObject.NULL))
            }
        })
        .put("gateways", JSONObject()
            .put("mercadopago", JSONObject().put("enabled", gateways.mercadoPago).put("pix", gateways.pix)
                .put("payer_email", gateways.payerEmail))
            .put("blink", JSONObject().put("enabled", gateways.blink))
            .put("stars", JSONObject().put("enabled", gateways.stars)))

    companion object {
        const val DEFAULT_STORE_URL = "https://alerrandrov.github.io/Easy-Telebot/loja/"
        /** Comandos da loja no Telegram (reservados: não podem virar comandos comuns). */
        val TRIGGERS = listOf("loja", "catalogo")
        const val SUBSCRIPTIONS_TRIGGER = "assinaturas"
        val CURRENCIES = listOf("BRL", "USD", "EUR", "ARS", "MXN", "CLP", "COP", "PEN", "UYU")
        private val SYMBOLS = mapOf(
            "BRL" to "R$", "USD" to "US$", "EUR" to "€", "ARS" to "ARS$", "MXN" to "MX$",
            "CLP" to "CLP$", "COP" to "COP$", "PEN" to "S/", "UYU" to "UY$",
        )

        /** Igual a `format_price` do motor: "R$ 1.234,56". */
        fun formatPrice(cents: Long, currency: String): String {
            val symbol = SYMBOLS[currency] ?: currency
            val whole = "%,d".format(java.util.Locale.ROOT, cents / 100).replace(',', '.')
            return if (currency == "CLP" || currency == "COP") "$symbol $whole"
            else "$symbol $whole,%02d".format(cents % 100)
        }

        /** "19,90" / "19.9" / "19" -> centavos. `null` se não for um valor. */
        fun parsePrice(text: String): Long? {
            val clean = text.trim().replace(" ", "")
            if (clean.isEmpty()) return null
            val normalized = if (clean.contains(',')) clean.replace(".", "").replace(',', '.') else clean
            val value = normalized.toBigDecimalOrNull() ?: return null
            if (value.signum() < 0) return null
            return value.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
        }

        fun priceText(cents: Long): String = "%d,%02d".format(cents / 100, cents % 100)

        fun fromJson(o: JSONObject?): ShopSettings {
            if (o == null) return ShopSettings()
            val g = o.optJSONObject("gateways") ?: JSONObject()
            val mp = g.optJSONObject("mercadopago") ?: JSONObject()
            val arr = o.optJSONArray("products") ?: JSONArray()
            val products = (0 until arr.length()).mapNotNull { i ->
                val p = arr.optJSONObject(i) ?: return@mapNotNull null
                Product(
                    id = p.optString("id").ifEmpty { UUID.randomUUID().toString().take(12) },
                    name = p.optString("name"),
                    description = p.optString("description"),
                    price = p.optLong("price"),
                    stars = if (p.isNull("stars")) null else p.optInt("stars").takeIf { it > 0 },
                    type = if (p.optString("type") == Product.TYPE_SUBSCRIPTION) Product.TYPE_SUBSCRIPTION else Product.TYPE_DIGITAL,
                    active = p.optBoolean("active", true),
                    stock = p.optInt("stock", -1),
                    photo = if (p.isNull("photo")) null else p.optString("photo").ifEmpty { null },
                    imageUrl = p.optString("image_url"),
                    chatId = if (p.isNull("chat_id")) null else p.optLong("chat_id").takeIf { it != 0L },
                    chatTitle = p.optString("chat_title"),
                    days = p.optInt("days", 30),
                    delivery = p.optJSONObject("delivery")?.let { BotConfig.parseMessage(it) },
                )
            }
            return ShopSettings(
                enabled = o.optBoolean("enabled", false),
                currency = o.optString("currency", "BRL").takeIf { it in CURRENCIES } ?: "BRL",
                intro = o.optString("intro", "Confira nossos produtos:"),
                miniapp = o.optBoolean("miniapp", true),
                storeUrl = o.optString("store_url", DEFAULT_STORE_URL).ifBlank { DEFAULT_STORE_URL },
                catalogUrl = o.optString("catalog_url"),
                gistId = o.optString("gist_id"),
                publishedHash = o.optString("published_hash"),
                notifyLevel = o.optInt("notify_level", 1).coerceIn(1, AdminLevels.MAX),
                remindDays = o.optInt("remind_days", 3).coerceIn(0, 30),
                products = products,
                gateways = ShopGateways(
                    mercadoPago = mp.optBoolean("enabled"),
                    pix = mp.optBoolean("pix"),
                    payerEmail = mp.optString("payer_email"),
                    blink = g.optJSONObject("blink")?.optBoolean("enabled") ?: false,
                    stars = g.optJSONObject("stars")?.optBoolean("enabled") ?: false,
                ),
            )
        }
    }
}

/** Credenciais dos gateways: cifradas no Keystore, nunca no `config.json`. */
data class PaymentSecrets(
    val mercadoPagoToken: String = "",
    val blinkKey: String = "",
    /** Vitrine: chave do imgbb (fotos) e token do GitHub só com permissão de Gists (catálogo). */
    val imgbbKey: String = "",
    val githubToken: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("mp_token", mercadoPagoToken).put("blink_key", blinkKey)
        .put("imgbb_key", imgbbKey).put("github_token", githubToken)
}

object PaymentRepository {
    private fun name(botId: Long) = "payments_$botId"

    fun load(context: Context, botId: Long): PaymentSecrets =
        TokenStore.loadSecret(context, name(botId))?.let {
            try {
                val o = JSONObject(it)
                PaymentSecrets(o.optString("mp_token"), o.optString("blink_key"), o.optString("imgbb_key"), o.optString("github_token"))
            } catch (e: Exception) {
                null
            }
        } ?: PaymentSecrets()

    fun save(context: Context, botId: Long, secrets: PaymentSecrets) {
        TokenStore.saveSecret(context, name(botId), secrets.toJson().toString())
    }

    fun delete(context: Context, botId: Long) = TokenStore.deleteSecret(context, name(botId))

    fun engineJson(context: Context, botId: Long): String = load(context, botId).toJson().toString()
}
