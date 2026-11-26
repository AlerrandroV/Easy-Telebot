package io.github.alerrandrov.easytelebot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.ConfigRepository
import io.github.alerrandrov.easytelebot.data.Coupon
import io.github.alerrandrov.easytelebot.data.PaymentRepository
import io.github.alerrandrov.easytelebot.data.PaymentSecrets
import io.github.alerrandrov.easytelebot.data.Product
import io.github.alerrandrov.easytelebot.data.ShopSettings
import io.github.alerrandrov.easytelebot.data.TokenStore
import io.github.alerrandrov.easytelebot.engine.BotRuntime
import io.github.alerrandrov.easytelebot.engine.EngineError
import io.github.alerrandrov.easytelebot.engine.ErrorExplainer
import io.github.alerrandrov.easytelebot.engine.PythonBridge
import io.github.alerrandrov.easytelebot.engine.ShopBridge
import io.github.alerrandrov.easytelebot.engine.ShopOrder
import io.github.alerrandrov.easytelebot.engine.ShopPublisher
import io.github.alerrandrov.easytelebot.engine.ShopStats
import io.github.alerrandrov.easytelebot.engine.Subscriber
import io.github.alerrandrov.easytelebot.service.BotService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private enum class ShopTab(val title: Int, val icon: Int) {
    PRODUCTS(R.string.shop_tab_products, R.drawable.ic_inventory_2),
    ORDERS(R.string.shop_tab_orders, R.drawable.ic_receipt_long),
    SUBSCRIBERS(R.string.shop_tab_subscribers, R.drawable.ic_card_membership),
    SETTINGS(R.string.shop_tab_settings, R.drawable.ic_payments),
}

/** Vendas: produtos, pedidos, assinantes de canais e configuração (gateways, vitrine). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopScreen(
    botId: Long,
    onEditProduct: (id: String?, subscription: Boolean) -> Unit,
    onOpenChat: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val dir = remember { BotRepository.dir(botId).path }
    val versions by BotRuntime.shopVersion.collectAsStateWithLifecycle()
    var localVersion by remember { mutableIntStateOf(0) }
    val version = (versions[botId] ?: 0L) + localVersion
    var config by remember { mutableStateOf(ConfigRepository.load(botId)) }
    // Publicar a vitrine grava o link do catálogo na config: recarrega quando a loja muda.
    androidx.compose.runtime.LaunchedEffect(version) { config = ConfigRepository.load(botId) }

    fun saveShop(change: (ShopSettings) -> ShopSettings) {
        val current = ConfigRepository.load(botId)
        config = current.copy(shop = change(current.shop))
        ConfigRepository.save(botId, config)
    }

    Scaffold(contentWindowInsets = screenInsets, topBar = { AppBar(stringResource(R.string.shop_title), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                ShopTab.entries.forEachIndexed { i, t ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { AppIcon(t.icon) },
                        text = { Text(stringResource(t.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 48.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!config.shop.enabled && tab != ShopTab.SETTINGS.ordinal) {
                    item {
                        Card(
                            Modifier.fillMaxWidth().clickable { tab = ShopTab.SETTINGS.ordinal },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppIcon(R.drawable.ic_info)
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(R.string.shop_off_banner))
                            }
                        }
                    }
                }
                when (ShopTab.entries[tab]) {
                    ShopTab.PRODUCTS -> productsTab(config.shop, onEditProduct)
                    ShopTab.ORDERS -> item { OrdersTab(botId, dir, version, config.shop.currency, onOpenChat) { localVersion++ } }
                    ShopTab.SUBSCRIBERS -> item { SubscribersTab(botId, dir, version) { localVersion++ } }
                    ShopTab.SETTINGS -> item { ShopSettingsTab(botId, config.shop, ::saveShop) }
                }
            }
        }
    }
}

// -- Produtos -------------------------------------------------------------------------------

private fun LazyListScope.productsTab(shop: ShopSettings, onEdit: (String?, Boolean) -> Unit) {
    if (shop.products.isEmpty()) {
        item {
            Text(
                stringResource(R.string.shop_products_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    items(shop.products, key = { it.id }) { p -> ProductCard(p, shop.currency) { onEdit(p.id, p.isSubscription) } }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onEdit(null, false) }, modifier = Modifier.weight(1f)) {
                ButtonLabel(R.drawable.ic_add, stringResource(R.string.shop_new_product))
            }
            OutlinedButton(onClick = { onEdit(null, true) }, modifier = Modifier.weight(1f)) {
                ButtonLabel(R.drawable.ic_add, stringResource(R.string.shop_new_subscription))
            }
        }
    }
}

@Composable
private fun ProductCard(p: Product, currency: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(
                    when (p.type) {
                        Product.TYPE_SUBSCRIPTION -> R.drawable.ic_card_membership
                        Product.TYPE_PHYSICAL -> R.drawable.ic_local_shipping
                        Product.TYPE_DONATION -> R.drawable.ic_volunteer_activism
                        else -> R.drawable.ic_sell
                    },
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = buildList {
                    if (p.isDonation) add(stringResource(R.string.product_type_donation))
                    if (p.price > 0) add(
                        if (p.isDonation) stringResource(R.string.shop_from_price, ShopSettings.formatPrice(p.price, currency))
                        else ShopSettings.formatPrice(p.price, currency),
                    )
                    p.stars?.let { add(stringResource(R.string.shop_stars_n, it)) }
                    if (p.isSubscription) add(stringResource(R.string.shop_sub_detail, p.days, p.chatTitle.ifEmpty { "?" }))
                    if (p.stock >= 0) add(stringResource(R.string.shop_stock_n, p.stock))
                }
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!p.active) StatusChip(stringResource(R.string.shop_inactive), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusChip(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Surface(shape = RoundedCornerShape(50), color = bg, contentColor = fg) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

// -- Pedidos --------------------------------------------------------------------------------

@Composable
private fun OrdersTab(botId: Long, dir: String, version: Long, currency: String, onOpenChat: (Long) -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val since = remember { System.currentTimeMillis() - 30L * 86400_000 }
    val data by produceState<Pair<List<ShopOrder>, ShopStats>?>(null, version) {
        value = withContext(Dispatchers.IO) { ShopBridge.orders(context, dir) to ShopBridge.stats(context, dir, since) }
    }
    var open by remember { mutableStateOf<ShopOrder?>(null) }
    var shipping by remember { mutableStateOf<ShopOrder?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<EngineError?>(null) }
    val (orders, stats) = data ?: return

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.shop_stats_title), style = MaterialTheme.typography.labelLarge)
                val totals = stats.totals.entries.joinToString(" + ") { ShopSettings.formatPrice(it.value, it.key) }
                    .ifEmpty { ShopSettings.formatPrice(0, currency) }
                Text(totals, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.shop_stats_count, stats.count) +
                        if (stats.stars > 0) " · " + stringResource(R.string.shop_stars_n, stats.stars.toInt()) else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (orders.isEmpty()) {
            Text(stringResource(R.string.shop_orders_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        orders.forEach { o -> OrderRow(o) { open = o } }
    }

    open?.let { o ->
        AlertDialog(
            onDismissRequest = { open = null; error = null },
            title = { Text(stringResource(R.string.shop_order_n, o.id)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconLabel(R.drawable.ic_person, buyerName(o.buyer, o.username, o.userId))
                    o.items.forEach { i ->
                        Text("${i.qty}x ${i.name} · ${ShopSettings.formatPrice(i.price * i.qty, o.currency)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (o.discount > 0) {
                        IconLabel(R.drawable.ic_local_offer, stringResource(R.string.shop_order_coupon, o.coupon,
                            ShopSettings.formatPrice(o.discount, o.currency)), style = MaterialTheme.typography.bodySmall)
                    }
                    if (o.shipping > 0) {
                        Text(stringResource(R.string.shop_order_shipping, ShopSettings.formatPrice(o.shipping, o.currency)),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text(orderTotal(o), fontWeight = FontWeight.Bold)
                    if (o.address.isNotBlank()) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            IconLabel(R.drawable.ic_local_shipping, o.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (o.shipped.isNotBlank()) {
                        IconLabel(R.drawable.ic_check_circle,
                            if (o.shipped == "-") stringResource(R.string.shop_order_shipped)
                            else stringResource(R.string.shop_order_shipped_code, o.shipped),
                            style = MaterialTheme.typography.bodySmall, tint = MaterialTheme.colorScheme.primary)
                    }
                    IconLabel(R.drawable.ic_account_balance_wallet, gatewayLabel(o.gateway), style = MaterialTheme.typography.bodySmall)
                    IconLabel(R.drawable.ic_schedule, formatDateTime(o.createdAt), style = MaterialTheme.typography.bodySmall)
                    if (o.status == "paid" && !o.delivered) {
                        IconLabel(R.drawable.ic_warning, stringResource(R.string.shop_delivery_problem, o.note), tint = MaterialTheme.colorScheme.error)
                    }
                    error?.let { ExplanationCard(ErrorExplainer.explain(context, it)) }
                }
            },
            confirmButton = {
                if (o.status == "pending" || o.status == "expired") {
                    TextButton(enabled = !busy, onClick = {
                        val token = TokenStore.load(context, botId) ?: return@TextButton
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                ShopBridge.confirmManually(context, token, dir, o.id, PaymentRepository.engineJson(context, botId))
                            }
                            busy = false
                            when (r) {
                                is PythonBridge.Result.Ok -> { open = null; onChanged() }
                                is PythonBridge.Result.Failed -> error = r.error
                            }
                        }
                    }) { Text(stringResource(R.string.shop_confirm_manual)) }
                } else if (o.status == "paid" && o.hasPhysical && o.shipped.isBlank()) {
                    TextButton(onClick = { shipping = o; open = null }) { Text(stringResource(R.string.shop_mark_shipped)) }
                } else {
                    TextButton(onClick = { open = null }) { Text(stringResource(R.string.close)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { open = null; onOpenChat(o.userId) }) { Text(stringResource(R.string.antispam_open_chat)) }
            },
        )
    }
    shipping?.let { o -> ShipDialog(botId, dir, o, onDismiss = { shipping = null }) { shipping = null; onChanged() } }
}

@Composable
private fun ShipDialog(botId: Long, dir: String, o: ShopOrder, onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tracking by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<EngineError?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shop_mark_shipped)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.shop_ship_msg), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = tracking, onValueChange = { tracking = it.take(100) },
                    label = { Text(stringResource(R.string.shop_tracking_code)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                error?.let { ExplanationCard(ErrorExplainer.explain(context, it)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val token = TokenStore.load(context, botId) ?: return@TextButton
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { ShopBridge.markShipped(context, token, dir, o.id, tracking.trim()) }
                    busy = false
                    when (r) {
                        is PythonBridge.Result.Ok -> onDone()
                        is PythonBridge.Result.Failed -> error = r.error
                    }
                }
            }) { Text(stringResource(R.string.shop_ship_notify)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun OrderRow(o: ShopOrder, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${o.id} · ${buyerName(o.buyer, o.username, o.userId)}",
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    o.items.joinToString(", ") { if (it.qty > 1) "${it.qty}x ${it.name}" else it.name },
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("${orderTotal(o)} · ${formatDateTime(o.createdAt)}", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            val cs = MaterialTheme.colorScheme
            when {
                o.status == "paid" && o.hasPhysical && o.shipped.isBlank() ->
                    StatusChip(stringResource(R.string.shop_status_to_ship), cs.tertiaryContainer, cs.onTertiaryContainer)
                o.status == "paid" && !o.delivered -> StatusChip(stringResource(R.string.shop_status_problem), cs.errorContainer, cs.onErrorContainer)
                o.status == "paid" -> StatusChip(stringResource(R.string.shop_status_paid), cs.primary, cs.onPrimary)
                o.status == "pending" -> StatusChip(stringResource(R.string.shop_status_pending), cs.tertiaryContainer, cs.onTertiaryContainer)
                o.status == "cancelled" -> StatusChip(stringResource(R.string.shop_status_cancelled), cs.surfaceVariant, cs.onSurfaceVariant)
                else -> StatusChip(stringResource(R.string.shop_status_expired), cs.surfaceVariant, cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun orderTotal(o: ShopOrder): String =
    if (o.gateway == "xtr") stringResource(R.string.shop_stars_n, o.stars ?: 0) else ShopSettings.formatPrice(o.total, o.currency)

@Composable
private fun gatewayLabel(gateway: String): String = stringResource(
    when (gateway) {
        "mp" -> R.string.shop_gw_mp
        "pix" -> R.string.shop_gw_pix
        "btc" -> R.string.shop_gw_btc
        "xtr" -> R.string.shop_gw_stars
        "manual" -> R.string.shop_gw_manual
        "stripe" -> R.string.shop_gw_stripe
        "paypal" -> R.string.shop_gw_paypal
        "coupon" -> R.string.shop_gw_coupon
        else -> R.string.shop_gw_none
    },
)

private fun buyerName(name: String, username: String, id: Long) = when {
    name.isNotBlank() && username.isNotBlank() -> "$name (@$username)"
    name.isNotBlank() -> name
    username.isNotBlank() -> "@$username"
    else -> id.toString()
}

private fun formatDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

// -- Assinantes -----------------------------------------------------------------------------

@Composable
private fun SubscribersTab(botId: Long, dir: String, version: Long, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val subs by produceState<List<Subscriber>?>(null, version) {
        value = withContext(Dispatchers.IO) { ShopBridge.subscribers(context, dir) }
    }
    var confirmRemove by remember { mutableStateOf<Subscriber?>(null) }
    var error by remember { mutableStateOf<EngineError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val list = subs ?: return

    fun run(block: suspend (String) -> PythonBridge.Result<*>) {
        val token = TokenStore.load(context, botId) ?: return
        busy = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { block(token) }
            busy = false
            if (r is PythonBridge.Result.Failed) error = r.error
            onChanged()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val active = list.count { it.active }
        Text(stringResource(R.string.shop_subs_summary, active, list.size - active), style = MaterialTheme.typography.bodyMedium)
        error?.let { ExplanationCard(ErrorExplainer.explain(context, it)) }
        if (list.isEmpty()) {
            Text(stringResource(R.string.shop_subs_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        list.forEach { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(buyerName(s.name, s.username, s.userId), style = MaterialTheme.typography.titleSmall)
                            IconLabel(
                                R.drawable.ic_campaign, s.chatTitle.ifEmpty { s.chatId.toString() },
                                style = MaterialTheme.typography.bodySmall, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val cs = MaterialTheme.colorScheme
                        if (s.active) StatusChip(stringResource(R.string.shop_sub_active), cs.primary, cs.onPrimary)
                        else StatusChip(stringResource(R.string.shop_sub_ended), cs.surfaceVariant, cs.onSurfaceVariant)
                    }
                    IconLabel(
                        R.drawable.ic_schedule,
                        stringResource(if (s.active) R.string.shop_sub_until else R.string.shop_sub_ended_on,
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.expiresAt))),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy, onClick = {
                            run { t -> ShopBridge.extendSubscription(context, t, dir, s.userId, s.chatId, 30) }
                        }) { ButtonLabel(R.drawable.ic_add, stringResource(R.string.shop_sub_extend)) }
                        if (s.active) {
                            OutlinedButton(enabled = !busy, onClick = { confirmRemove = s }) {
                                ButtonLabel(R.drawable.ic_block, stringResource(R.string.shop_sub_remove))
                            }
                        }
                    }
                }
            }
        }
    }

    confirmRemove?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.shop_sub_remove_title, buyerName(s.name, s.username, s.userId))) },
            text = { Text(stringResource(R.string.shop_sub_remove_msg, s.chatTitle)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    run { t -> ShopBridge.removeSubscription(context, t, dir, s.userId, s.chatId) }
                }) { Text(stringResource(R.string.shop_sub_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// -- Vitrine na nuvem (fotos + catálogo) ----------------------------------------------------

@Composable
private fun StorefrontCloud(
    botId: Long,
    shop: ShopSettings,
    secrets: PaymentSecrets,
    onSecrets: (PaymentSecrets) -> Unit,
    saved: PaymentSecrets,
    saveSecrets: (PaymentSecrets) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var publishing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<EngineError?>(null) }
    var result by remember { mutableStateOf<ShopPublisher.Published?>(null) }
    val outdated = remember(shop) { ShopPublisher.isOutdated(botId) }

    Text(stringResource(R.string.storefront_cloud), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.storefront_cloud_msg), style = MaterialTheme.typography.bodySmall)
    SecretField(stringResource(R.string.storefront_github_token), secrets.githubToken) { onSecrets(secrets.copy(githubToken = it.trim())) }
    StepList(stringArrayResource(R.array.storefront_github_steps).toList())
    SecretField(stringResource(R.string.storefront_imgbb_key), secrets.imgbbKey) { onSecrets(secrets.copy(imgbbKey = it.trim())) }
    Text(stringResource(R.string.storefront_imgbb_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    IconLabel(
        when {
            shop.catalogUrl.isBlank() -> R.drawable.ic_info
            outdated -> R.drawable.ic_warning
            else -> R.drawable.ic_check_circle
        },
        when {
            shop.catalogUrl.isBlank() -> stringResource(R.string.storefront_not_published)
            outdated -> stringResource(R.string.storefront_outdated)
            else -> stringResource(R.string.storefront_published)
        },
        style = MaterialTheme.typography.bodySmall,
        tint = if (outdated) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
    result?.let {
        Text(stringResource(R.string.storefront_result, it.products, (it.bytes + 1023) / 1024), style = MaterialTheme.typography.bodySmall)
    }
    error?.let { ExplanationCard(ErrorExplainer.explain(context, it)) }
    Button(
        enabled = !publishing && secrets.githubToken.isNotBlank(),
        onClick = {
            if (secrets != saved) saveSecrets(secrets)
            publishing = true
            error = null
            result = null
            scope.launch {
                val r = withContext(Dispatchers.IO) { ShopPublisher.publish(context, botId) }
                publishing = false
                when (r) {
                    is PythonBridge.Result.Ok -> result = r.value
                    is PythonBridge.Result.Failed -> error = r.error
                }
                BotRuntime.bumpShop(botId)
            }
        },
    ) { ButtonLabel(R.drawable.ic_storefront, stringResource(if (publishing) R.string.storefront_publishing else R.string.storefront_publish)) }
    if (shop.catalogUrl.isNotBlank()) {
        TextButton(onClick = { ShopPublisher.unpublish(botId); BotRuntime.bumpShop(botId) }) {
            Text(stringResource(R.string.storefront_unpublish))
        }
    }
}

// -- Configuração ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShopSettingsTab(botId: Long, shop: ShopSettings, save: ((ShopSettings) -> ShopSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var secrets by remember { mutableStateOf(PaymentRepository.load(context, botId)) }
    val saved = remember { mutableStateOf(secrets) }
    var intro by remember { mutableStateOf(shop.intro) }
    var storeUrl by remember { mutableStateOf(shop.storeUrl) }
    var payerEmail by remember { mutableStateOf(shop.gateways.payerEmail) }
    var testing by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<Pair<String, String>?>(null) }
    var testError by remember { mutableStateOf<EngineError?>(null) }

    fun saveSecrets(new: PaymentSecrets) {
        PaymentRepository.save(context, botId, new)
        saved.value = new
        BotService.restartIfRunning(context, botId) // o motor recebe as credenciais ao ligar
    }

    fun test(which: String) {
        testing = which
        testResult = null
        testError = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                when (which) {
                    "mp" -> ShopBridge.testMercadoPago(context, secrets.mercadoPagoToken)
                    "stripe" -> ShopBridge.testStripe(context, secrets.stripeKey)
                    "paypal" -> ShopBridge.testPaypal(context, secrets.paypalClientId, secrets.paypalSecret, shop.gateways.paypalSandbox)
                    else -> ShopBridge.testBlink(context, secrets.blinkKey)
                }
            }
            testing = null
            when (r) {
                is PythonBridge.Result.Ok -> {
                    testResult = which to r.value.optString("name")
                    if (secrets != saved.value) saveSecrets(secrets)
                }
                is PythonBridge.Result.Failed -> testError = r.error
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column {
                SwitchRow(stringResource(R.string.shop_enable), stringResource(R.string.shop_enable_sub), shop.enabled) { v ->
                    save { it.copy(enabled = v) }
                }
                HorizontalDivider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.shop_currency), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ShopSettings.CURRENCIES.forEach { c ->
                            FilterChip(selected = shop.currency == c, onClick = { save { it.copy(currency = c) } }, label = { Text(c) })
                        }
                    }
                    Text(stringResource(R.string.shop_currency_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = intro, onValueChange = { intro = it.take(1000) },
                        label = { Text(stringResource(R.string.shop_intro)) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                    )
                    if (intro != shop.intro) {
                        Button(onClick = { save { it.copy(intro = intro) } }) { Text(stringResource(R.string.save)) }
                    }
                }
            }
        }

        SectionTitle(stringResource(R.string.shop_payments))
        testError?.let { ExplanationCard(ErrorExplainer.explain(context, it)) }

        // Mercado Pago
        GatewayCard(
            icon = R.drawable.ic_payments,
            title = stringResource(R.string.shop_gw_mp_title),
            subtitle = stringResource(R.string.shop_gw_mp_sub),
            enabled = shop.gateways.mercadoPago,
            onEnabled = { v -> save { it.copy(gateways = it.gateways.copy(mercadoPago = v)) } },
        ) {
            SecretField(stringResource(R.string.shop_mp_token), secrets.mercadoPagoToken) { secrets = secrets.copy(mercadoPagoToken = it.trim()) }
            StepList(listOf(stringResource(R.string.shop_mp_how)))
            TestRow(
                testing = testing == "mp",
                ok = testResult?.takeIf { it.first == "mp" }?.second,
                canTest = secrets.mercadoPagoToken.isNotBlank(),
                onTest = { test("mp") },
            )
            HorizontalDivider()
            SwitchRow(stringResource(R.string.shop_pix), stringResource(R.string.shop_pix_sub), shop.gateways.pix) { v ->
                save { it.copy(gateways = it.gateways.copy(pix = v)) }
            }
            if (shop.gateways.pix) {
                OutlinedTextField(
                    value = payerEmail, onValueChange = { payerEmail = it.trim().take(120) },
                    label = { Text(stringResource(R.string.shop_payer_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (payerEmail != shop.gateways.payerEmail) {
                    Button(onClick = { save { it.copy(gateways = it.gateways.copy(payerEmail = payerEmail)) } }) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }

        // Blink
        GatewayCard(
            icon = R.drawable.ic_bolt,
            title = stringResource(R.string.shop_gw_blink_title),
            subtitle = stringResource(R.string.shop_gw_blink_sub),
            enabled = shop.gateways.blink,
            onEnabled = { v -> save { it.copy(gateways = it.gateways.copy(blink = v)) } },
        ) {
            SecretField(stringResource(R.string.shop_blink_key), secrets.blinkKey) { secrets = secrets.copy(blinkKey = it.trim()) }
            StepList(listOf(stringResource(R.string.shop_blink_how)))
            TestRow(
                testing = testing == "btc",
                ok = testResult?.takeIf { it.first == "btc" }?.second,
                canTest = secrets.blinkKey.isNotBlank(),
                onTest = { test("btc") },
            )
        }

        // Telegram Stars
        GatewayCard(
            icon = R.drawable.ic_star,
            title = stringResource(R.string.shop_gw_stars_title),
            subtitle = stringResource(R.string.shop_gw_stars_sub),
            enabled = shop.gateways.stars,
            onEnabled = { v -> save { it.copy(gateways = it.gateways.copy(stars = v)) } },
        ) {}

        // Stripe
        GatewayCard(
            icon = R.drawable.ic_credit_card,
            title = stringResource(R.string.shop_gw_stripe_title),
            subtitle = stringResource(R.string.shop_gw_stripe_sub),
            enabled = shop.gateways.stripe,
            onEnabled = { v -> save { it.copy(gateways = it.gateways.copy(stripe = v)) } },
        ) {
            SecretField(stringResource(R.string.shop_stripe_key), secrets.stripeKey) { secrets = secrets.copy(stripeKey = it.trim()) }
            StepList(stringArrayResource(R.array.shop_stripe_steps).toList())
            TestRow(
                testing = testing == "stripe",
                ok = testResult?.takeIf { it.first == "stripe" }?.second,
                canTest = secrets.stripeKey.isNotBlank(),
                onTest = { test("stripe") },
            )
        }

        // PayPal
        GatewayCard(
            icon = R.drawable.ic_account_balance_wallet,
            title = stringResource(R.string.shop_gw_paypal_title),
            subtitle = stringResource(R.string.shop_gw_paypal_sub),
            enabled = shop.gateways.paypal,
            onEnabled = { v -> save { it.copy(gateways = it.gateways.copy(paypal = v)) } },
        ) {
            SecretField(stringResource(R.string.shop_paypal_client_id), secrets.paypalClientId) { secrets = secrets.copy(paypalClientId = it.trim()) }
            SecretField(stringResource(R.string.shop_paypal_secret), secrets.paypalSecret) { secrets = secrets.copy(paypalSecret = it.trim()) }
            StepList(stringArrayResource(R.array.shop_paypal_steps).toList())
            SwitchRow(stringResource(R.string.shop_paypal_sandbox), stringResource(R.string.shop_paypal_sandbox_sub), shop.gateways.paypalSandbox) { v ->
                save { it.copy(gateways = it.gateways.copy(paypalSandbox = v)) }
            }
            TestRow(
                testing = testing == "paypal",
                ok = testResult?.takeIf { it.first == "paypal" }?.second,
                canTest = secrets.paypalClientId.isNotBlank() && secrets.paypalSecret.isNotBlank(),
                onTest = { test("paypal") },
            )
        }

        // Pix com chave própria / pagamento combinado
        GatewayCard(
            icon = R.drawable.ic_qr_code_2,
            title = stringResource(R.string.shop_gw_manual_title),
            subtitle = stringResource(R.string.shop_gw_manual_sub),
            enabled = shop.gateways.manual,
            onEnabled = { v -> save { it.copy(gateways = it.gateways.copy(manual = v)) } },
        ) { ManualGatewayFields(shop, save) }

        if (secrets != saved.value) {
            Button(onClick = { saveSecrets(secrets) }, modifier = Modifier.fillMaxWidth()) {
                ButtonLabel(R.drawable.ic_key, stringResource(R.string.shop_save_keys))
            }
        }
        Text(stringResource(R.string.shop_keys_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionTitle(stringResource(R.string.shop_sales_extras))
        CouponsCard(shop, save)
        ShippingCard(shop, save)
        Card(Modifier.fillMaxWidth()) {
            Column {
                SwitchRow(stringResource(R.string.shop_abandoned), stringResource(R.string.shop_abandoned_sub), shop.abandoned) { v ->
                    save { it.copy(abandoned = v) }
                }
                if (shop.abandoned) {
                    FlowRow(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(10, 20, 30, 50).forEach { m ->
                            FilterChip(
                                selected = shop.abandonedMinutes == m, onClick = { save { it.copy(abandonedMinutes = m) } },
                                label = { Text(stringResource(R.string.shop_after_minutes, m)) },
                            )
                        }
                    }
                }
            }
        }

        SectionTitle(stringResource(R.string.shop_miniapp))
        Card(Modifier.fillMaxWidth()) {
            Column {
                SwitchRow(stringResource(R.string.shop_miniapp_enable), stringResource(R.string.shop_miniapp_enable_sub), shop.miniapp) { v ->
                    save { it.copy(miniapp = v) }
                }
                if (shop.miniapp) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = storeUrl, onValueChange = { storeUrl = it.trim().take(300) },
                            label = { Text(stringResource(R.string.shop_store_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            isError = !storeUrl.startsWith("https://"),
                        )
                        if (storeUrl != shop.storeUrl && storeUrl.startsWith("https://")) {
                            Button(onClick = { save { it.copy(storeUrl = storeUrl) } }) { Text(stringResource(R.string.save)) }
                        }
                        Text(stringResource(R.string.shop_store_url_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        StorefrontCloud(botId, shop, secrets, { secrets = it }, saved.value, ::saveSecrets)
                    }
                }
            }
        }

        SectionTitle(stringResource(R.string.shop_notifications))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.shop_notify_level), style = MaterialTheme.typography.titleSmall)
                LevelPicker(shop.notifyLevel, explain = { l -> adminClassExplanationShort(l) }) { l -> save { it.copy(notifyLevel = l) } }
                HorizontalDivider()
                Text(stringResource(R.string.shop_remind), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 1, 3, 7).forEach { d ->
                        FilterChip(
                            selected = shop.remindDays == d, onClick = { save { it.copy(remindDays = d) } },
                            label = { Text(if (d == 0) stringResource(R.string.shop_remind_off) else stringResource(R.string.product_days_n, d)) },
                        )
                    }
                }
            }
        }
        Text(stringResource(R.string.shop_how_title), style = MaterialTheme.typography.titleSmall)
        StepList(androidx.compose.ui.res.stringArrayResource(R.array.shop_how_steps).toList())
    }
}

@Composable
private fun GatewayCard(
    icon: Int,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(icon, tint = MaterialTheme.colorScheme.primary)
            }
            SwitchRow(title, subtitle, enabled, onEnabled)
            if (enabled) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            androidx.compose.material3.IconButton(onClick = { show = !show }) {
                AppIcon(R.drawable.ic_visibility, description = stringResource(R.string.shop_show_key))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TestRow(testing: Boolean, ok: String?, canTest: Boolean, onTest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onTest, enabled = canTest && !testing) {
            Text(stringResource(if (testing) R.string.shop_testing else R.string.shop_test))
        }
        if (ok != null) {
            Spacer(Modifier.width(12.dp))
            IconLabel(
                R.drawable.ic_check_circle, stringResource(R.string.shop_test_ok, ok.ifEmpty { "-" }),
                style = MaterialTheme.typography.bodySmall, tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// -- Pix com chave própria, cupons e frete ----------------------------------------------------

@Composable
private fun ManualGatewayFields(shop: ShopSettings, save: ((ShopSettings) -> ShopSettings) -> Unit) {
    val g = shop.gateways
    var pixKey by remember { mutableStateOf(g.pixKey) }
    var pixName by remember { mutableStateOf(g.pixName) }
    var pixCity by remember { mutableStateOf(g.pixCity) }
    var label by remember { mutableStateOf(g.manualLabel) }
    var instructions by remember { mutableStateOf(g.manualInstructions) }
    val changed = pixKey != g.pixKey || pixName != g.pixName || pixCity != g.pixCity ||
        label != g.manualLabel || instructions != g.manualInstructions
    val pixIncomplete = pixKey.isNotBlank() && (pixName.isBlank() || pixCity.isBlank())

    Text(stringResource(R.string.shop_manual_explain), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = pixKey, onValueChange = { pixKey = it.trim().take(77) },
        label = { Text(stringResource(R.string.shop_pix_key)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    if (pixKey.isNotBlank()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = pixName, onValueChange = { pixName = it.take(25) },
                label = { Text(stringResource(R.string.shop_pix_name)) }, singleLine = true, modifier = Modifier.weight(1f),
                isError = pixName.isBlank(),
            )
            OutlinedTextField(
                value = pixCity, onValueChange = { pixCity = it.take(15) },
                label = { Text(stringResource(R.string.shop_pix_city)) }, singleLine = true, modifier = Modifier.weight(1f),
                isError = pixCity.isBlank(),
            )
        }
        Text(stringResource(R.string.shop_pix_key_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    OutlinedTextField(
        value = label, onValueChange = { label = it.take(40) },
        label = { Text(stringResource(R.string.shop_manual_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.shop_manual_label_hint)) },
    )
    OutlinedTextField(
        value = instructions, onValueChange = { instructions = it.take(2000) },
        label = { Text(stringResource(R.string.shop_manual_instructions)) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
    )
    if (changed) {
        Button(enabled = !pixIncomplete, onClick = {
            save {
                it.copy(gateways = it.gateways.copy(pixKey = pixKey, pixName = pixName.trim(), pixCity = pixCity.trim(),
                    manualLabel = label.trim(), manualInstructions = instructions))
            }
        }) { Text(stringResource(R.string.save)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CouponsCard(shop: ShopSettings, save: ((ShopSettings) -> ShopSettings) -> Unit) {
    var editing by remember { mutableStateOf<Pair<Int, Coupon>?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconLabel(R.drawable.ic_local_offer, stringResource(R.string.shop_coupons), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.shop_coupons_sub), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            shop.coupons.forEachIndexed { i, c ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { editing = i to c }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.code, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        val parts = buildList {
                            add(if (c.percent > 0) "-${c.percent}%" else "-" + ShopSettings.formatPrice(c.amount, shop.currency))
                            if (c.maxUses >= 0) add(stringResource(R.string.shop_coupon_uses_n, c.maxUses))
                            if (c.expires.isNotEmpty()) add(stringResource(R.string.shop_coupon_until, c.expires))
                        }
                        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    if (!c.active) StatusChip(stringResource(R.string.shop_inactive), MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedButton(onClick = { editing = -1 to Coupon() }) {
                ButtonLabel(R.drawable.ic_add, stringResource(R.string.shop_coupon_add))
            }
        }
    }
    editing?.let { (index, coupon) ->
        CouponDialog(
            coupon, shop.currency, canDelete = index >= 0,
            onDismiss = { editing = null },
            onDelete = { save { s -> s.copy(coupons = s.coupons.filterIndexed { i, _ -> i != index }) }; editing = null },
        ) { c ->
            save { s ->
                val list = s.coupons.filterIndexed { i, _ -> i != index }.filter { it.code != c.code }
                s.copy(coupons = if (index >= 0) list.toMutableList().apply { add(index.coerceAtMost(size), c) } else list + c)
            }
            editing = null
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CouponDialog(
    coupon: Coupon,
    currency: String,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (Coupon) -> Unit,
) {
    var code by remember { mutableStateOf(coupon.code) }
    var isPercent by remember { mutableStateOf(coupon.amount <= 0) }
    var value by remember {
        mutableStateOf(if (coupon.amount > 0) ShopSettings.priceText(coupon.amount) else coupon.percent.takeIf { it > 0 }?.toString() ?: "10")
    }
    var limit by remember { mutableStateOf(coupon.maxUses.takeIf { it >= 0 }?.toString() ?: "") }
    var expires by remember { mutableStateOf(coupon.expires) }
    var active by remember { mutableStateOf(coupon.active) }
    val percent = value.toIntOrNull()
    val amount = ShopSettings.parsePrice(value)
    val dateOk = expires.isBlank() || Regex("""^\d{4}-\d{2}-\d{2}$""").matches(expires.trim())
    val valid = code.isNotBlank() && dateOk &&
        (if (isPercent) percent != null && percent in 1..100 else amount != null && amount > 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (canDelete) R.string.shop_coupon_edit else R.string.shop_coupon_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = code, onValueChange = { code = Coupon.cleanCode(it) },
                    label = { Text(stringResource(R.string.shop_coupon_code)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = isPercent, onClick = { isPercent = true }, label = { Text(stringResource(R.string.shop_coupon_percent)) })
                    FilterChip(selected = !isPercent, onClick = { isPercent = false },
                        label = { Text(stringResource(R.string.shop_coupon_amount, currency)) })
                }
                OutlinedTextField(
                    value = value, onValueChange = { value = it.filter { c -> c.isDigit() || (!isPercent && (c == ',' || c == '.')) }.take(10) },
                    label = { Text(if (isPercent) "%" else currency) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = limit, onValueChange = { limit = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.shop_coupon_limit)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
                OutlinedTextField(
                    value = expires, onValueChange = { expires = it.filter { c -> c.isDigit() || c == '-' }.take(10) },
                    label = { Text(stringResource(R.string.shop_coupon_expires)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("2026-12-31") }, isError = !dateOk,
                )
                SwitchRow(stringResource(R.string.shop_coupon_active), null, active) { active = it }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(Coupon(
                    code = code, percent = if (isPercent) percent ?: 0 else 0, amount = if (isPercent) 0 else amount ?: 0,
                    maxUses = limit.toIntOrNull() ?: -1, expires = expires.trim(), active = active,
                ))
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (canDelete) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun ShippingCard(shop: ShopSettings, save: ((ShopSettings) -> ShopSettings) -> Unit) {
    var fee by remember { mutableStateOf(if (shop.shippingFee > 0) ShopSettings.priceText(shop.shippingFee) else "") }
    var freeOver by remember { mutableStateOf(if (shop.freeShippingOver > 0) ShopSettings.priceText(shop.freeShippingOver) else "") }
    val feeValue = if (fee.isBlank()) 0L else ShopSettings.parsePrice(fee)
    val freeValue = if (freeOver.isBlank()) 0L else ShopSettings.parsePrice(freeOver)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconLabel(R.drawable.ic_local_shipping, stringResource(R.string.shop_shipping), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.shop_shipping_sub), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = fee, onValueChange = { fee = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(10) },
                    label = { Text(stringResource(R.string.shop_shipping_fee, shop.currency)) }, singleLine = true,
                    modifier = Modifier.weight(1f), isError = feeValue == null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = freeOver, onValueChange = { freeOver = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(10) },
                    label = { Text(stringResource(R.string.shop_shipping_free_over)) }, singleLine = true,
                    modifier = Modifier.weight(1f), isError = freeValue == null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                )
            }
            if (feeValue != null && freeValue != null && (feeValue != shop.shippingFee || freeValue != shop.freeShippingOver)) {
                Button(onClick = { save { it.copy(shippingFee = feeValue, freeShippingOver = freeValue) } }) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}
