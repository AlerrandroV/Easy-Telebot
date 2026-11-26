package io.github.alerrandrov.easytelebot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.AdminLevels
import io.github.alerrandrov.easytelebot.data.ConfigRepository
import io.github.alerrandrov.easytelebot.data.DeskSettings
import io.github.alerrandrov.easytelebot.data.FaqItem

/** Atendimento: modo suporte (mensagens chegam aos admins), horário de atendimento e perguntas frequentes. */
@Composable
fun DeskScreen(botId: Long, onBack: () -> Unit) {
    var config by remember { mutableStateOf(ConfigRepository.load(botId)) }
    val d = config.desk
    fun update(new: DeskSettings) {
        config = config.copy(desk = new)
        ConfigRepository.save(botId, config)
    }
    var editing by remember { mutableStateOf<Int?>(null) }

    Scaffold(contentWindowInsets = screenInsets, topBar = { AppBar(stringResource(R.string.desk_title), onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.desk_intro), style = MaterialTheme.typography.bodyMedium) }

            item { SectionTitle(stringResource(R.string.desk_support)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        SwitchRow(stringResource(R.string.desk_support_enable), stringResource(R.string.desk_support_sub), d.support) {
                            update(d.copy(support = it))
                        }
                        if (d.support) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.desk_support_level), style = MaterialTheme.typography.titleSmall)
                            LevelPicker(d.supportLevel, explain = { l -> stringResource(R.string.desk_support_level_explain, AdminLevels.roman(l)) }) {
                                update(d.copy(supportLevel = it))
                            }
                            var ack by remember { mutableStateOf(d.ack) }
                            OutlinedTextField(
                                value = ack,
                                onValueChange = { ack = it.take(1000); update(config.desk.copy(ack = ack)) },
                                label = { Text(stringResource(R.string.desk_ack)) },
                                supportingText = { Text(stringResource(R.string.desk_ack_hint)) },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Note(stringResource(R.string.desk_support_note))
                        }
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.desk_hours)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        SwitchRow(stringResource(R.string.desk_hours_enable), stringResource(R.string.desk_hours_sub), d.hours) { update(d.copy(hours = it)) }
                        if (d.hours) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            WeekdayChips(d.days) { update(d.copy(days = it)) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TimeButton(stringResource(R.string.desk_from), d.start, Modifier.weight(1f)) { update(d.copy(start = it)) }
                                TimeButton(stringResource(R.string.desk_to), d.end, Modifier.weight(1f)) { update(d.copy(end = it)) }
                            }
                            var away by remember { mutableStateOf(d.away) }
                            OutlinedTextField(
                                value = away,
                                onValueChange = { away = it.take(2000); update(config.desk.copy(away = away)) },
                                label = { Text(stringResource(R.string.desk_away)) },
                                supportingText = { Text(stringResource(R.string.desk_away_hint)) },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.desk_faq)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        SwitchRow(stringResource(R.string.desk_faq_enable), stringResource(R.string.desk_faq_sub, d.faqCommand), d.faq) { update(d.copy(faq = it)) }
                    }
                }
            }
            if (d.faq) {
                itemsIndexed(d.faqItems) { i, item ->
                    Card(onClick = { editing = i }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.question, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(item.answer, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { update(d.copy(faqItems = d.faqItems.filterIndexed { j, _ -> j != i })) }) {
                                AppIcon(R.drawable.ic_delete, Modifier.size(20.dp), description = stringResource(R.string.delete))
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = { update(d.copy(faqItems = d.faqItems + FaqItem())); editing = d.faqItems.size }, modifier = Modifier.fillMaxWidth()) {
                        ButtonLabel(R.drawable.ic_add, stringResource(R.string.desk_faq_add))
                    }
                }
                item { Note(stringResource(R.string.desk_faq_note)) }
            }
        }
    }

    editing?.let { i ->
        val item = d.faqItems.getOrNull(i)
        if (item == null) {
            editing = null
        } else {
            FaqDialog(item, onDismiss = {
                // pergunta nova deixada em branco some
                if (item.question.isBlank() && item.answer.isBlank()) update(d.copy(faqItems = d.faqItems.filterIndexed { j, _ -> j != i }))
                editing = null
            }) { changed -> update(d.copy(faqItems = d.faqItems.mapIndexed { j, x -> if (j == i) changed else x })) }
        }
    }
}

@Composable
private fun FaqDialog(item: FaqItem, onDismiss: () -> Unit, onChange: (FaqItem) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.desk_faq_item)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(item.question, { onChange(item.copy(question = it.take(300))) }, label = { Text(stringResource(R.string.desk_faq_q)) },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(item.answer, { onChange(item.copy(answer = it.take(4000))) }, label = { Text(stringResource(R.string.desk_faq_a)) },
                    minLines = 3, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.desk_faq_keywords), style = MaterialTheme.typography.titleSmall)
                WordsInput(item.keywords, stringResource(R.string.desk_faq_keyword), { onChange(item.copy(keywords = it.take(20))) }, splitOnComma = false)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}
