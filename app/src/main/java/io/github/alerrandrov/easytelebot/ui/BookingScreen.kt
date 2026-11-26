package io.github.alerrandrov.easytelebot.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.BookingSettings
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.ConfigRepository
import io.github.alerrandrov.easytelebot.data.TokenStore
import io.github.alerrandrov.easytelebot.engine.PythonBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Agenda de horários: configuração (dias, horas, duração) e os próximos agendamentos. */
@Composable
fun BookingScreen(botId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(ConfigRepository.load(botId)) }
    val b = config.booking
    fun update(new: BookingSettings) {
        config = config.copy(booking = new)
        ConfigRepository.save(botId, config)
    }
    var reload by remember { mutableIntStateOf(0) }
    var bookings by remember { mutableStateOf<List<PythonBridge.BookingItem>>(emptyList()) }
    LaunchedEffect(reload) {
        bookings = withContext(Dispatchers.IO) { PythonBridge.listBookings(context, BotRepository.dir(botId).path) }
    }
    val today = remember { java.time.LocalDateTime.now().toString().take(16) }

    Scaffold(contentWindowInsets = screenInsets, topBar = { AppBar(stringResource(R.string.booking_title), onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.booking_intro), style = MaterialTheme.typography.bodyMedium) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchRow(stringResource(R.string.booking_enable), stringResource(R.string.booking_enable_sub, b.command, b.myCommand), b.enabled) {
                        update(b.copy(enabled = it))
                    }
                }
            }
            if (b.enabled) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            var service by remember { mutableStateOf(b.service) }
                            OutlinedTextField(service, { service = it.take(100); update(config.booking.copy(service = service)) },
                                label = { Text(stringResource(R.string.booking_service)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.booking_days), style = MaterialTheme.typography.titleSmall)
                            WeekdayChips(b.days) { update(b.copy(days = it)) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TimeButton(stringResource(R.string.desk_from), b.start, Modifier.weight(1f)) { update(b.copy(start = it)) }
                                TimeButton(stringResource(R.string.desk_to), b.end, Modifier.weight(1f)) { update(b.copy(end = it)) }
                            }
                            Text(stringResource(R.string.booking_slot), style = MaterialTheme.typography.titleSmall)
                            ChoiceChips(listOf(15, 30, 45, 60, 90, 120), b.slot, { durationText(it) }) { update(b.copy(slot = it)) }
                            Text(stringResource(R.string.booking_capacity), style = MaterialTheme.typography.titleSmall)
                            ChoiceChips(listOf(1, 2, 3, 5, 10), b.capacity, { it.toString() }) { update(b.copy(capacity = it)) }
                            Text(stringResource(R.string.booking_ahead), style = MaterialTheme.typography.titleSmall)
                            ChoiceChips(listOf(7, 14, 30, 60), b.ahead, { stringResource(R.string.dur_days, it) }) { update(b.copy(ahead = it)) }
                            Text(stringResource(R.string.booking_remind), style = MaterialTheme.typography.titleSmall)
                            ChoiceChips(listOf(0, 1, 2, 24), b.remindHours, {
                                if (it == 0) stringResource(R.string.mod_off) else durationText(it * 60)
                            }) { update(b.copy(remindHours = it)) }
                            var confirm by remember { mutableStateOf(b.confirm) }
                            OutlinedTextField(confirm, { confirm = it.take(2000); update(config.booking.copy(confirm = confirm)) },
                                label = { Text(stringResource(R.string.booking_confirm)) },
                                supportingText = { Text(stringResource(R.string.booking_confirm_hint)) },
                                minLines = 2, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                item { Note(stringResource(R.string.booking_note)) }
            }

            val upcoming = bookings.filter { it.at >= today }
            item { SectionTitle(stringResource(R.string.booking_upcoming, upcoming.size)) }
            if (upcoming.isEmpty()) item {
                Text(stringResource(R.string.booking_empty), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(upcoming, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(formatWhen(item.at), style = MaterialTheme.typography.titleSmall)
                            Text(item.name + if (item.username.isNotBlank()) " (@${item.username})" else "", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    val token = TokenStore.load(context, botId).orEmpty()
                                    PythonBridge.cancelBooking(context, token, BotRepository.configFile(botId).path, item.id)
                                }
                                Toast.makeText(context, context.getString(if (ok) R.string.booking_cancelled else R.string.booking_cancel_failed), Toast.LENGTH_SHORT).show()
                                reload++
                            }
                        }) { Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun formatWhen(iso: String): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    return runCatching {
        java.time.LocalDateTime.parse(iso).format(
            java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT).withLocale(locale)
        )
    }.getOrDefault(iso)
}
