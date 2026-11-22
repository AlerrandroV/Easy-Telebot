package io.github.alerrandrov.easytelebot.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class OrderItem(val id: String, val name: String, val qty: Int, val price: Long, val type: String)

data class ShopOrder(
    val id: String,
    val userId: Long,
    val buyer: String,
    val username: String,
    val items: List<OrderItem>,
    val total: Long,
    val currency: String,
    val stars: Int?,
    /** "mp", "pix", "btc", "xtr" ou vazio (ainda não escolheu). */
    val gateway: String,
    /** pending, paid, expired, cancelled */
    val status: String,
    val createdAt: Long,
    val paidAt: Long,
    val delivered: Boolean,
    val note: String,
)

data class Subscriber(
    val userId: Long,
    val chatId: Long,
    val productId: String,
    val name: String,
    val username: String,
    val chatTitle: String,
    val startedAt: Long,
    val expiresAt: Long,
    /** active, expired, removed */
    val status: String,
) {
    val active get() = status == "active" && expiresAt > System.currentTimeMillis()
}

data class ShopStats(val count: Int, val totals: Map<String, Long>, val stars: Long)

/** Ponte para `shop/` (pedidos, assinaturas, gateways). Tudo bloqueia: chame fora da thread principal. */
object ShopBridge {
    private fun db(context: Context) = PythonBridge.module(context, "shop.db")
    private fun flow(context: Context) = PythonBridge.module(context, "shop.flow")
    private fun gateways(context: Context) = PythonBridge.module(context, "shop.gateways")

    private fun result(raw: String): PythonBridge.Result<JSONObject> {
        val o = JSONObject(raw)
        return if (o.optBoolean("ok")) PythonBridge.Result.Ok(o)
        else PythonBridge.Result.Failed(EngineError.fromJson(o.getJSONObject("error")))
    }

    private inline fun guard(block: () -> PythonBridge.Result<JSONObject>): PythonBridge.Result<JSONObject> = try {
        block()
    } catch (e: Exception) {
        PythonBridge.Result.Failed(EngineError("pay_unknown", e.message ?: e.toString()))
    }

    /** Fecha o banco de pedidos (antes de apagar o bot). */
    fun close(context: Context, dir: String) {
        runCatching { db(context).callAttr("close", dir) }
    }

    fun orders(context: Context, dir: String): List<ShopOrder> = try {
        val arr = JSONArray(db(context).callAttr("list_orders", dir).toString())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val items = o.optJSONArray("items") ?: JSONArray()
            ShopOrder(
                id = o.optString("id"),
                userId = o.optLong("user_id"),
                buyer = listOf(o.optString("first_name"), o.optString("last_name")).filter { it.isNotBlank() }.joinToString(" "),
                username = o.optString("username"),
                items = (0 until items.length()).map { j ->
                    val it = items.getJSONObject(j)
                    OrderItem(it.optString("id"), it.optString("name"), it.optInt("qty", 1), it.optLong("price"), it.optString("type"))
                },
                total = o.optLong("total"),
                currency = o.optString("currency"),
                stars = if (o.isNull("stars")) null else o.optInt("stars"),
                gateway = if (o.isNull("gateway")) "" else o.optString("gateway"),
                status = o.optString("status"),
                createdAt = o.optLong("created_at") * 1000,
                paidAt = o.optLong("paid_at") * 1000,
                delivered = o.optInt("delivered") == 1,
                note = if (o.isNull("note")) "" else o.optString("note"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun subscribers(context: Context, dir: String): List<Subscriber> = try {
        val arr = JSONArray(db(context).callAttr("list_subscriptions", dir).toString())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Subscriber(
                userId = o.optLong("user_id"),
                chatId = o.optLong("chat_id"),
                productId = o.optString("product_id"),
                name = listOf(o.optString("first_name"), o.optString("last_name")).filter { it.isNotBlank() }.joinToString(" "),
                username = o.optString("username"),
                chatTitle = o.optString("chat_title"),
                startedAt = o.optLong("started_at") * 1000,
                expiresAt = o.optLong("expires_at") * 1000,
                status = o.optString("status"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Vendas pagas desde `sinceMillis`. */
    fun stats(context: Context, dir: String, sinceMillis: Long): ShopStats = try {
        val o = JSONObject(db(context).callAttr("stats", dir, sinceMillis / 1000).toString())
        val totals = o.optJSONObject("totals") ?: JSONObject()
        ShopStats(o.optInt("count"), totals.keys().asSequence().associateWith { totals.optLong(it) }, o.optLong("stars"))
    } catch (e: Exception) {
        ShopStats(0, emptyMap(), 0)
    }

    fun confirmManually(context: Context, token: String, dir: String, orderId: String, paymentsJson: String) =
        guard { result(flow(context).callAttr("app_confirm", token, dir, orderId, paymentsJson).toString()) }

    fun extendSubscription(context: Context, token: String, dir: String, userId: Long, chatId: Long, days: Int) =
        guard { result(flow(context).callAttr("app_subscription", token, dir, userId, chatId, "extend", days).toString()) }

    fun removeSubscription(context: Context, token: String, dir: String, userId: Long, chatId: Long) =
        guard { result(flow(context).callAttr("app_subscription", token, dir, userId, chatId, "remove", 0).toString()) }

    /** Testa o token do Mercado Pago. Ok = `name` (apelido da conta) e `currency`. */
    fun testMercadoPago(context: Context, token: String) =
        guard { result(gateways(context).callAttr("test_mercadopago", token).toString()) }

    /** Testa a chave do Blink. Ok = `name` (usuário) e `balance` (sats). */
    fun testBlink(context: Context, key: String) =
        guard { result(gateways(context).callAttr("test_blink", key).toString()) }
}
