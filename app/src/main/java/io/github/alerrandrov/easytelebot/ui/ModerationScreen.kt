package io.github.alerrandrov.easytelebot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.ConfigRepository
import io.github.alerrandrov.easytelebot.data.ModerationSettings

/**
 * Moderação de grupos: verificação na entrada, filtros, advertências, comandos de moderação,
 * modo lento e regras. Cada mudança é salva na hora (o motor recarrega sozinho).
 */
@Composable
fun ModerationScreen(botId: Long, onBack: () -> Unit) {
    var config by remember { mutableStateOf(ConfigRepository.load(botId)) }
    val m = config.moderation
    fun update(new: ModerationSettings) {
        config = config.copy(moderation = new)
        ConfigRepository.save(botId, config)
    }

    Scaffold(contentWindowInsets = screenInsets, topBar = { AppBar(stringResource(R.string.mod_title), onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.mod_intro), style = MaterialTheme.typography.bodyMedium) }
            item { Note(stringResource(R.string.mod_rights_note)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchRow(stringResource(R.string.mod_enable), stringResource(R.string.mod_enable_sub), m.enabled) { update(m.copy(enabled = it)) }
                }
            }
            if (!m.enabled) return@LazyColumn

            item { SectionTitle(stringResource(R.string.mod_section_join)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        SwitchRow(stringResource(R.string.mod_captcha), stringResource(R.string.mod_captcha_sub), m.captcha) { update(m.copy(captcha = it)) }
                        if (m.captcha) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.mod_captcha_mode), style = MaterialTheme.typography.titleSmall)
                            ChoiceChips(listOf("button", "math"), m.captchaMode, {
                                stringResource(if (it == "math") R.string.mod_captcha_math else R.string.mod_captcha_button)
                            }) { update(m.copy(captchaMode = it)) }
                            Text(stringResource(R.string.mod_captcha_timeout), style = MaterialTheme.typography.titleSmall)
                            ChoiceChips(listOf(1, 3, 5, 10), m.captchaTimeout, { durationText(it) }) { update(m.copy(captchaTimeout = it)) }
                            Text(stringResource(R.string.mod_captcha_fail), style = MaterialTheme.typography.titleSmall)
                            ChoiceChips(listOf("kick", "mute"), m.captchaFail, {
                                stringResource(if (it == "mute") R.string.mod_captcha_fail_mute else R.string.mod_captcha_fail_kick)
                            }) { update(m.copy(captchaFail = it)) }
                        }
                        HorizontalDivider()
                        SwitchRow(stringResource(R.string.mod_delete_service), stringResource(R.string.mod_delete_service_sub), m.deleteService) {
                            update(m.copy(deleteService = it))
                        }
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.mod_section_filters)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        SwitchRow(stringResource(R.string.mod_links), stringResource(R.string.mod_links_sub), m.links) { update(m.copy(links = it)) }
                        if (m.links) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            WordsInput(m.allowedDomains, stringResource(R.string.mod_allowed_domains), {
                                update(m.copy(allowedDomains = it.map { d -> d.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore("/") }))
                            })
                        }
                        HorizontalDivider()
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.mod_words), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.mod_words_sub), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            WordsInput(m.words, stringResource(R.string.mod_word), { update(m.copy(words = it)) })
                        }
                        HorizontalDivider()
                        SwitchRow(stringResource(R.string.mod_forwards), stringResource(R.string.mod_forwards_sub), m.forwards) { update(m.copy(forwards = it)) }
                        HorizontalDivider()
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.mod_newcomer), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.mod_newcomer_sub), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ChoiceChips(listOf(0, 1, 24, 72, 168), m.newcomerHours, {
                                if (it == 0) stringResource(R.string.mod_off) else durationText(it * 60)
                            }) { update(m.copy(newcomerHours = it)) }
                            Text(stringResource(R.string.mod_filter_action), style = MaterialTheme.typography.titleSmall)
                            ChoiceChips(listOf("warn", "delete"), m.filterAction, {
                                stringResource(if (it == "delete") R.string.mod_action_delete else R.string.mod_action_warn)
                            }) { update(m.copy(filterAction = it)) }
                        }
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.mod_section_warnings)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.mod_warn_limit), style = MaterialTheme.typography.titleSmall)
                        ChoiceChips(listOf(1, 2, 3, 5), m.warnLimit, { it.toString() }) { update(m.copy(warnLimit = it)) }
                        Text(stringResource(R.string.mod_warn_action), style = MaterialTheme.typography.titleSmall)
                        ChoiceChips(listOf("mute", "kick", "ban"), m.warnAction, {
                            stringResource(when (it) { "kick" -> R.string.mod_kick; "ban" -> R.string.mod_ban; else -> R.string.mod_mute })
                        }) { update(m.copy(warnAction = it)) }
                        if (m.warnAction == "mute") {
                            Text(stringResource(R.string.mod_mute_for), style = MaterialTheme.typography.titleSmall)
                            ChoiceChips(listOf(1, 24, 72, 168), m.muteHours, { durationText(it * 60) }) { update(m.copy(muteHours = it)) }
                        }
                        Text(stringResource(R.string.mod_warn_help, m.warnLimit), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.mod_section_more)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        SwitchRow(stringResource(R.string.mod_commands), stringResource(R.string.mod_commands_sub), m.commands) { update(m.copy(commands = it)) }
                        if (m.commands) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                            stringArrayResource(R.array.mod_command_list).forEach {
                                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                        HorizontalDivider()
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.mod_slowmode), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.mod_slowmode_sub), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ChoiceChips(listOf(0, 10, 30, 60, 300), m.slowmode, {
                                when {
                                    it == 0 -> stringResource(R.string.mod_off)
                                    it < 60 -> stringResource(R.string.dur_seconds, it)
                                    else -> durationText(it / 60)
                                }
                            }) { update(m.copy(slowmode = it)) }
                        }
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.mod_rules)) }
            item {
                var rules by remember { mutableStateOf(m.rules) }
                OutlinedTextField(
                    value = rules,
                    onValueChange = { rules = it.take(4000); update(config.moderation.copy(rules = rules)) },
                    label = { Text(stringResource(R.string.mod_rules_text)) },
                    supportingText = { Text(stringResource(R.string.mod_rules_sub)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
