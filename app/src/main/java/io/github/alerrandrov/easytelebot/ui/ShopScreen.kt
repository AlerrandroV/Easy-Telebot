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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.ConfigRepository
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

    fun saveShop(change: (ShopSettings) -> ShopSettings) {
        val current = ConfigRepository.load(botId)
        config = current.copy(shop = change(current.shop))
        ConfigRepository.save(botId, config)
    }

    Scaffold(topBar = { AppBar(stringResource(R.string.shop_title), onBack) }) { padding ->
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
                    if (p.isSubscription) R.drawable.ic_card_membership else R.drawable.ic_sell,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = buildList {
                    if (p.price > 0) add(ShopSettings.formatPrice(p.price, currency))
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
                    Text(orderTotal(o), fontWeight = FontWeight.Bold)
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
                } else {
                    TextButton(onClick = { open = null }) { Text(stringResource(R.string.close)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { open = null; onOpenChat(o.userId) }) { Text(stringResource(R.string.antispam_open_chat)) }
            },
        )
    }
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
                if (which == "mp") ShopBridge.testMercadoPago(context, secrets.mercadoPagoToken)
                else ShopBridge.testBlink(context, secrets.blinkKey)
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

        if (secrets != saved.value) {
            Button(onClick = { saveSecrets(secrets) }, modifier = Modifier.fillMaxWidth()) {
                ButtonLabel(R.drawable.ic_key, stringResource(R.string.shop_save_keys))
            }
        }
        Text(stringResource(R.string.shop_keys_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

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
