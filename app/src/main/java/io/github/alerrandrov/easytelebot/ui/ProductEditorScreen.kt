package io.github.alerrandrov.easytelebot.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.BotMessage
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.ConfigRepository
import io.github.alerrandrov.easytelebot.data.MediaImporter
import io.github.alerrandrov.easytelebot.data.Product
import io.github.alerrandrov.easytelebot.data.ShopSettings
import io.github.alerrandrov.easytelebot.engine.ChatSummary
import io.github.alerrandrov.easytelebot.engine.ChatsBridge
import io.github.alerrandrov.easytelebot.engine.ShopPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val SUBSCRIPTION_DAYS = listOf(7, 30, 90, 365)

/**
 * Produto da loja: nome, descrição, preço, foto, estoque e o que a pessoa recebe.
 * Assinatura de canal: o canal/grupo e por quantos dias; o bot manda um convite pessoal.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProductEditorScreen(botId: Long, productId: String?, subscription: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val config = remember { ConfigRepository.load(botId) }
    val original = remember { config.shop.products.firstOrNull { it.id == productId } }
    val currency = config.shop.currency

    var name by remember { mutableStateOf(original?.name ?: "") }
    var description by remember { mutableStateOf(original?.description ?: "") }
    var price by remember { mutableStateOf(original?.let { ShopSettings.priceText(it.price) } ?: "") }
    var stars by remember { mutableStateOf(original?.stars?.toString() ?: "") }
    var active by remember { mutableStateOf(original?.active ?: true) }
    var limited by remember { mutableStateOf((original?.stock ?: -1) >= 0) }
    var stock by remember { mutableStateOf(original?.stock?.takeIf { it >= 0 }?.toString() ?: "10") }
    var photo by remember { mutableStateOf(original?.photo) }
    var imageUrl by remember { mutableStateOf(original?.imageUrl ?: "") }
    val isSubscription = original?.isSubscription ?: subscription
    var chatId by remember { mutableStateOf(original?.chatId) }
    var chatTitle by remember { mutableStateOf(original?.chatTitle ?: "") }
    var days by remember { mutableStateOf(original?.days?.toString() ?: "30") }
    var delivery by remember { mutableStateOf(Draft(0, original?.delivery ?: BotMessage())) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }

    val groups by produceState<List<ChatSummary>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
            runCatching { ChatsBridge.listChats(context, BotRepository.dir(botId).path) }.getOrDefault(emptyList())
                .filter { it.isGroup || it.type == "channel" }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val m = MediaImporter.import(context, botId, uri)
        if (m == null || m.type != "photo") {
            Toast.makeText(context, context.getString(R.string.product_photo_invalid), Toast.LENGTH_LONG).show()
        } else {
            photo = m.file
        }
    }

    val errName = stringResource(R.string.product_err_name)
    val errPrice = stringResource(R.string.product_err_price)
    val errChat = stringResource(R.string.product_err_chat)
    val errDays = stringResource(R.string.product_err_days)
    val errStock = stringResource(R.string.product_err_stock)
    val errDelivery = stringResource(R.string.product_err_delivery)

    fun save() {
        val cents = ShopSettings.parsePrice(price)
        val starsValue = stars.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        val daysValue = days.trim().toIntOrNull()
        val stockValue = stock.trim().toIntOrNull()
        val msg = delivery.msg
        errors = buildList {
            if (name.isBlank()) add(errName)
            if ((cents == null || cents <= 0) && (starsValue == null || starsValue <= 0)) add(errPrice)
            if (isSubscription && chatId == null) add(errChat)
            if (isSubscription && (daysValue == null || daysValue !in 1..3650)) add(errDays)
            if (limited && (stockValue == null || stockValue < 0)) add(errStock)
            if (!isSubscription && msg.text.isBlank() && msg.media == null) add(errDelivery)
        }
        if (errors.isNotEmpty()) return
        val product = (original ?: Product(type = if (isSubscription) Product.TYPE_SUBSCRIPTION else Product.TYPE_DIGITAL)).copy(
            name = name.trim(),
            description = description.trim(),
            price = cents ?: 0,
            stars = starsValue?.takeIf { it > 0 },
            active = active,
            stock = if (limited) stockValue ?: 0 else -1,
            photo = photo,
            imageUrl = imageUrl.trim(),
            chatId = if (isSubscription) chatId else null,
            chatTitle = if (isSubscription) chatTitle else "",
            days = if (isSubscription) daysValue ?: 30 else 30,
            delivery = if (isSubscription) null else msg,
        )
        val current = ConfigRepository.load(botId)
        val list = current.shop.products
        val updated = if (list.any { it.id == product.id }) list.map { if (it.id == product.id) product else it } else list + product
        ConfigRepository.save(botId, current.copy(shop = current.shop.copy(products = updated)))
        ShopPublisher.publishInBackground(context, botId) // vitrine com fotos: republica sozinha
        onDone()
    }

    Scaffold(
        topBar = {
            AppBar(
                stringResource(
                    when {
                        original != null -> R.string.product_edit_title
                        isSubscription -> R.string.add_subscription
                        else -> R.string.add_product
                    },
                ),
                onDone,
            ) {
                if (original != null) {
                    IconButton(onClick = { confirmDelete = true }) {
                        AppIcon(R.drawable.ic_delete, tint = MaterialTheme.colorScheme.error, description = stringResource(R.string.delete))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(if (isSubscription) R.string.product_intro_subscription else R.string.product_intro_digital),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(64) },
                    label = { Text(stringResource(R.string.product_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = description, onValueChange = { description = it.take(700) },
                    label = { Text(stringResource(R.string.product_description)) },
                    minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(12) },
                        label = { Text(stringResource(R.string.product_price, currency)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = stars, onValueChange = { stars = it.filter(Char::isDigit).take(6) },
                        label = { Text(stringResource(R.string.product_stars)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    stringResource(R.string.product_stars_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isSubscription) {
                item { SectionTitle(stringResource(R.string.product_channel)) }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (groups.isEmpty()) {
                                Text(stringResource(R.string.product_channel_empty), style = MaterialTheme.typography.bodyMedium)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                groups.forEach { g ->
                                    FilterChip(
                                        selected = g.chatId == chatId,
                                        onClick = { chatId = g.chatId; chatTitle = g.displayName },
                                        label = { Text(g.displayName) },
                                        leadingIcon = {
                                            AppIcon(if (g.isGroup) R.drawable.ic_group else R.drawable.ic_campaign, Modifier.size(18.dp))
                                        },
                                    )
                                }
                            }
                            if (chatId != null && groups.none { it.chatId == chatId }) {
                                Text(stringResource(R.string.product_channel_current, chatTitle), style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                stringResource(R.string.product_channel_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    Text(stringResource(R.string.product_days), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SUBSCRIPTION_DAYS.forEach { d ->
                            FilterChip(selected = days == d.toString(), onClick = { days = d.toString() },
                                label = { Text(stringResource(R.string.product_days_n, d)) })
                        }
                    }
                    OutlinedTextField(
                        value = days, onValueChange = { days = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.product_days_custom)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.product_photo)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val path = photo?.let { File(BotRepository.dir(botId), it).path }
                        val bmp = remember(path) { path?.let { android.graphics.BitmapFactory.decodeFile(it) } }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (bmp != null) {
                                Image(
                                    bmp.asImageBitmap(), null,
                                    Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop,
                                )
                                Spacer(Modifier.width(12.dp))
                            }
                            OutlinedButton(onClick = { photoPicker.launch(arrayOf("image/*")) }) {
                                ButtonLabel(R.drawable.ic_image, stringResource(if (photo == null) R.string.product_photo_pick else R.string.product_photo_change))
                            }
                            if (photo != null) {
                                IconButton(onClick = { photo = null }) { AppIcon(R.drawable.ic_close, description = stringResource(R.string.delete)) }
                            }
                        }
                        OutlinedTextField(
                            value = imageUrl, onValueChange = { imageUrl = it.trim().take(500) },
                            label = { Text(stringResource(R.string.product_image_url)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.product_image_url_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.product_sale)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        SwitchRow(stringResource(R.string.product_active), stringResource(R.string.product_active_sub), active) { active = it }
                        if (!isSubscription) {
                            HorizontalDivider()
                            SwitchRow(stringResource(R.string.product_limited), stringResource(R.string.product_limited_sub), limited) { limited = it }
                            if (limited) {
                                OutlinedTextField(
                                    value = stock, onValueChange = { stock = it.filter(Char::isDigit).take(6) },
                                    label = { Text(stringResource(R.string.product_stock)) },
                                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (!isSubscription) {
                item { SectionTitle(stringResource(R.string.product_delivery)) }
                item {
                    Text(
                        stringResource(R.string.product_delivery_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    MessageEditor(
                        botId = botId,
                        index = 0,
                        count = 1,
                        draft = delivery,
                        onChange = { delivery = delivery.copy(msg = it) },
                        onMove = {},
                        onRemove = {},
                    )
                }
            }

            if (errors.isNotEmpty()) {
                item {
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            errors.forEach { IconLabel(R.drawable.ic_error, it, tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            item {
                Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) {
                    ButtonLabel(R.drawable.ic_check, stringResource(R.string.save))
                }
            }
        }
    }

    if (confirmDelete && original != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.product_delete_title, original.name)) },
            text = { Text(stringResource(R.string.product_delete_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    val current = ConfigRepository.load(botId)
                    ConfigRepository.save(botId, current.copy(shop = current.shop.copy(products = current.shop.products.filter { it.id != original.id })))
                    ShopPublisher.publishInBackground(context, botId)
                    confirmDelete = false
                    onDone()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
