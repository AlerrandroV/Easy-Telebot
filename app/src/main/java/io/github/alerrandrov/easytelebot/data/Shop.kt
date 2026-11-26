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
    /** "digital", "subscription", "physical" ou "donation". */
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
    /** Doação: valores sugeridos em centavos (o preço é o mínimo). */
    val amounts: List<Long> = emptyList(),
) {
    val isSubscription get() = type == TYPE_SUBSCRIPTION
    val isPhysical get() = type == TYPE_PHYSICAL
    val isDonation get() = type == TYPE_DONATION

    companion object {
        const val TYPE_DIGITAL = "digital"
        const val TYPE_SUBSCRIPTION = "subscription"
        const val TYPE_PHYSICAL = "physical"
        const val TYPE_DONATION = "donation"
        val TYPES = listOf(TYPE_DIGITAL, TYPE_PHYSICAL, TYPE_SUBSCRIPTION, TYPE_DONATION)
    }
}

/** Cupom de desconto: [percent] OU [amount] (centavos). [expires] = "aaaa-mm-dd" ou vazio. */
data class Coupon(
    val code: String = "",
    val percent: Int = 0,
    val amount: Long = 0,
    /** -1 = sem limite. */
    val maxUses: Int = -1,
    val expires: String = "",
    val active: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("code", code).put("percent", percent).put("amount", amount)
        .put("max_uses", maxUses).put("expires", expires).put("active", active)

    companion object {
        fun cleanCode(text: String) = text.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }.take(32)

        fun fromJson(o: JSONObject) = Coupon(
            code = o.optString("code"), percent = o.optInt("percent").coerceIn(0, 100), amount = o.optLong("amount"),
            maxUses = o.optInt("max_uses", -1), expires = o.optString("expires"), active = o.optBoolean("active", true),
        )
    }
}

data class ShopGateways(
    val mercadoPago: Boolean = false,
    /** Pix com QR code no chat (precisa de um e-mail de pagador). */
    val pix: Boolean = false,
    val payerEmail: String = "",
    val blink: Boolean = false,
    val stars: Boolean = false,
    /** Pagamento combinado: chave Pix (QR gerado no próprio app) ou instruções livres; o admin confirma. */
    val manual: Boolean = false,
    val manualLabel: String = "",
    val manualInstructions: String = "",
    val pixKey: String = "",
    val pixName: String = "",
    val pixCity: String = "",
    val stripe: Boolean = false,
    val paypal: Boolean = false,
    val paypalSandbox: Boolean = false,
) {
    val any get() = mercadoPago || blink || stars || manual || stripe || paypal
}

data class ShopSettings(
    val enabled: Boolean = false,
    val currency: String = "BRL",
    /** Vazio = texto padrão no idioma do bot. */
    val intro: String = "",
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
    val coupons: List<Coupon> = emptyList(),
    /** Frete fixo em centavos para produtos físicos; grátis a partir de [freeShippingOver] (0 = nunca). */
    val shippingFee: Long = 0,
    val freeShippingOver: Long = 0,
    /** Lembrete "você esqueceu algo" para quem não terminou o pagamento. */
    val abandoned: Boolean = false,
    val abandonedMinutes: Int = 30,
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
                    .put("delivery", p.delivery?.let { BotConfig.messageJson(it) } ?: JSONObject.NULL)
                    .put("amounts", JSONArray(p.amounts)))
            }
        })
        .put("gateways", JSONObject()
            .put("mercadopago", JSONObject().put("enabled", gateways.mercadoPago).put("pix", gateways.pix)
                .put("payer_email", gateways.payerEmail))
            .put("blink", JSONObject().put("enabled", gateways.blink))
            .put("stars", JSONObject().put("enabled", gateways.stars))
            .put("manual", JSONObject().put("enabled", gateways.manual).put("label", gateways.manualLabel)
                .put("instructions", gateways.manualInstructions).put("pix_key", gateways.pixKey)
                .put("pix_name", gateways.pixName).put("pix_city", gateways.pixCity))
            .put("stripe", JSONObject().put("enabled", gateways.stripe))
            .put("paypal", JSONObject().put("enabled", gateways.paypal).put("sandbox", gateways.paypalSandbox)))
        .put("coupons", JSONArray().apply { coupons.forEach { put(it.toJson()) } })
        .put("shipping", JSONObject().put("fee", shippingFee).put("free_over", freeShippingOver))
        .put("abandoned", JSONObject().put("enabled", abandoned).put("minutes", abandonedMinutes))

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
                    type = p.optString("type").takeIf { it in Product.TYPES } ?: Product.TYPE_DIGITAL,
                    active = p.optBoolean("active", true),
                    stock = p.optInt("stock", -1),
                    photo = if (p.isNull("photo")) null else p.optString("photo").ifEmpty { null },
                    imageUrl = p.optString("image_url"),
                    chatId = if (p.isNull("chat_id")) null else p.optLong("chat_id").takeIf { it != 0L },
                    chatTitle = p.optString("chat_title"),
                    days = p.optInt("days", 30),
                    delivery = p.optJSONObject("delivery")?.let { BotConfig.parseMessage(it) },
                    amounts = p.optJSONArray("amounts")?.let { a -> (0 until a.length()).map { a.optLong(it) }.filter { it > 0 } } ?: emptyList(),
                )
            }
            val manual = g.optJSONObject("manual") ?: JSONObject()
            val coupons = o.optJSONArray("coupons")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Coupon::fromJson) }.filter { it.code.isNotEmpty() }
            } ?: emptyList()
            val ship = o.optJSONObject("shipping") ?: JSONObject()
            val aband = o.optJSONObject("abandoned") ?: JSONObject()
            return ShopSettings(
                enabled = o.optBoolean("enabled", false),
                currency = o.optString("currency", "BRL").takeIf { it in CURRENCIES } ?: "BRL",
                intro = o.optString("intro", ""),
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
                    manual = manual.optBoolean("enabled"),
                    manualLabel = manual.optString("label"),
                    manualInstructions = manual.optString("instructions"),
                    pixKey = manual.optString("pix_key"),
                    pixName = manual.optString("pix_name"),
                    pixCity = manual.optString("pix_city"),
                    stripe = g.optJSONObject("stripe")?.optBoolean("enabled") ?: false,
                    paypal = g.optJSONObject("paypal")?.optBoolean("enabled") ?: false,
                    paypalSandbox = g.optJSONObject("paypal")?.optBoolean("sandbox") ?: false,
                ),
                coupons = coupons,
                shippingFee = ship.optLong("fee").coerceAtLeast(0),
                freeShippingOver = ship.optLong("free_over").coerceAtLeast(0),
                abandoned = aband.optBoolean("enabled", false),
                abandonedMinutes = aband.optInt("minutes", 30).coerceIn(10, 50),
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
    val stripeKey: String = "",
    val paypalClientId: String = "",
    val paypalSecret: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("mp_token", mercadoPagoToken).put("blink_key", blinkKey)
        .put("imgbb_key", imgbbKey).put("github_token", githubToken).put("stripe_key", stripeKey)
        .put("paypal_client_id", paypalClientId).put("paypal_secret", paypalSecret)
}

object PaymentRepository {
    private fun name(botId: Long) = "payments_$botId"

    fun load(context: Context, botId: Long): PaymentSecrets =
        TokenStore.loadSecret(context, name(botId))?.let {
            try {
                val o = JSONObject(it)
                PaymentSecrets(o.optString("mp_token"), o.optString("blink_key"), o.optString("imgbb_key"), o.optString("github_token"),
                    o.optString("stripe_key"), o.optString("paypal_client_id"), o.optString("paypal_secret"))
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
