package io.github.alerrandrov.easytelebot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.BotCommand
import io.github.alerrandrov.easytelebot.data.ConfigRepository
import io.github.alerrandrov.easytelebot.data.Variables

@Composable
fun CommandsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(ConfigRepository.load(context)) }
    // índice em edição: -1 = novo comando, null = nenhum
    var editing by remember { mutableStateOf<Int?>(null) }

    fun save(commands: List<BotCommand>) {
        config = config.copy(commands = commands)
        ConfigRepository.save(context, config)
    }

    Scaffold(
        topBar = { AppBar(stringResource(R.string.commands_title), onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = -1 }) {
                Text(stringResource(R.string.commands_add))
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.commands_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (config.commands.isEmpty()) {
                item { Text(stringResource(R.string.commands_empty), Modifier.padding(vertical = 24.dp)) }
            }
            itemsIndexed(config.commands) { index, cmd ->
                Card(onClick = { editing = index }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("/${cmd.command}", style = MaterialTheme.typography.titleMedium)
                        if (cmd.description.isNotBlank()) {
                            Text(cmd.description, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            cmd.response,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    editing?.let { index ->
        val existing = config.commands.getOrNull(index)
        CommandEditor(
            initial = existing,
            otherNames = config.commands.filterIndexed { i, _ -> i != index }.map { it.command },
            onDismiss = { editing = null },
            onSave = { cmd ->
                val list = config.commands.toMutableList()
                if (existing == null) list.add(cmd) else list[index] = cmd
                save(list)
                editing = null
            },
            onDelete = if (existing == null) null else {
                {
                    save(config.commands.filterIndexed { i, _ -> i != index })
                    editing = null
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandEditor(
    initial: BotCommand?,
    otherNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (BotCommand) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial?.command ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var response by remember { mutableStateOf(TextFieldValue(initial?.response ?: "")) }

    val cleanName = name.trim().removePrefix("/").lowercase()
    val nameError = when {
        cleanName.isEmpty() -> null
        !Variables.COMMAND_NAME.matches(cleanName) -> stringResource(R.string.cmd_name_invalid)
        cleanName in otherNames -> stringResource(R.string.cmd_name_duplicate)
        else -> null
    }
    val unknown = Variables.unknown(response.text)
    val canSave = cleanName.isNotEmpty() && nameError == null && response.text.isNotBlank()

    fun insertVariable(v: String) {
        val token = "{$v}"
        val t = response.text
        val start = response.selection.start.coerceIn(0, t.length)
        val end = response.selection.end.coerceIn(0, t.length)
        val newText = t.substring(0, minOf(start, end)) + token + t.substring(maxOf(start, end))
        val cursor = minOf(start, end) + token.length
        response = TextFieldValue(newText, TextRange(cursor))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.cmd_new else R.string.cmd_edit)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.cmd_name)) },
                    prefix = { Text("/") },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = { Text(nameError ?: stringResource(R.string.cmd_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(256) },
                    label = { Text(stringResource(R.string.cmd_description)) },
                    supportingText = { Text(stringResource(R.string.cmd_description_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = response,
                    onValueChange = { response = it },
                    label = { Text(stringResource(R.string.cmd_response)) },
                    minLines = 3,
                    isError = unknown.isNotEmpty(),
                    supportingText = {
                        if (unknown.isNotEmpty()) {
                            Text(stringResource(R.string.cmd_unknown_vars, unknown.joinToString { "{$it}" }))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.cmd_variables), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Variables.KNOWN.forEach { v ->
                        AssistChip(onClick = { insertVariable(v) }, label = { Text("{$v}") })
                    }
                }
                Text(
                    stringResource(R.string.cmd_variables_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.cmd_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        BotCommand(
                            command = cleanName,
                            description = description.trim(),
                            response = response.text,
                            adminOnly = initial?.adminOnly ?: false,
                        )
                    )
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
