package io.github.alerrandrov.easytelebot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.BotConfig
import io.github.alerrandrov.easytelebot.data.BotMessage
import io.github.alerrandrov.easytelebot.data.ButtonTypes
import io.github.alerrandrov.easytelebot.data.ConfigRepository
import io.github.alerrandrov.easytelebot.data.FlowAfter
import io.github.alerrandrov.easytelebot.data.FlowNode

/** Uma linha do fluxograma: a etapa, a profundidade e como se chega nela. */
private data class TreeRow(val node: FlowNode?, val depth: Int, val via: String, val repeatOf: FlowNode? = null, val missing: String? = null)

/** Monta a árvore a partir das raízes (etapas iniciadas por comandos ou que nada aponta). */
private fun buildTree(config: BotConfig, labels: TreeLabels): List<TreeRow> {
    val byId = config.flows.associateBy { it.id }
    val referenced = config.flows.flatMap { it.links() }.toSet()
    val started = config.commands.mapNotNull { c -> c.flow?.let { it to c } }
    val roots = (started.map { it.first } + config.flows.map { it.id }.filter { it !in referenced }).distinct()
    val out = mutableListOf<TreeRow>()
    val shown = mutableSetOf<String>()

    fun visit(id: String, depth: Int, via: String) {
        val node = byId[id]
        when {
            node == null -> out += TreeRow(null, depth, via, missing = id)
            id in shown -> out += TreeRow(null, depth, via, repeatOf = node)
            else -> {
                shown += id
                out += TreeRow(node, depth, via)
                node.messages.flatMap { it.buttons.flatten() }.filter { it.type == ButtonTypes.NODE }
                    .forEach { b -> visit(b.url, depth + 1, labels.button(b.text)) }
                node.after.node?.let { next ->
                    when (node.after.type) {
                        FlowAfter.GOTO -> visit(next, depth + 1, labels.next)
                        FlowAfter.ASK -> visit(next, depth + 1, labels.answer)
                    }
                }
            }
        }
    }
    roots.forEach { id ->
        val cmds = started.filter { it.first == id }.joinToString(", ") { "/" + it.second.triggers.firstOrNull().orEmpty() }
        visit(id, 0, if (cmds.isNotEmpty()) labels.command(cmds) else labels.loose)
    }
    // Sobrou algo (só dentro de um ciclo)? Mostra também.
    config.flows.filter { it.id !in shown }.forEach { visit(it.id, 0, labels.loose) }
    return out
}

private class TreeLabels(
    val next: String,
    val answer: String,
    val loose: String,
    private val buttonFmt: String,
    private val commandFmt: String,
) {
    fun button(text: String) = buttonFmt.format(text)
    fun command(cmds: String) = commandFmt.format(cmds)
}

/** Fluxos: as etapas da conversa e como elas se ligam (por botões, "em seguida" e respostas). */
@Composable
fun FlowsScreen(botId: Long, onEditNode: (String?) -> Unit, onBack: () -> Unit) {
    val config = remember { ConfigRepository.load(botId) }
    val labels = TreeLabels(
        next = stringResource(R.string.flow_via_next),
        answer = stringResource(R.string.flow_via_answer),
        loose = stringResource(R.string.flow_via_none),
        buttonFmt = stringResource(R.string.flow_via_button),
        commandFmt = stringResource(R.string.flow_via_command),
    )
    val rows = remember(config) { buildTree(config, labels) }

    Scaffold(
        topBar = { AppBar(stringResource(R.string.flows_title), onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { onEditNode(null) }) { ButtonLabel(R.drawable.ic_add, stringResource(R.string.flow_new_node)) }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text(stringResource(R.string.flows_intro), style = MaterialTheme.typography.bodyMedium) }
            if (config.flows.isEmpty()) {
                item { StepList(androidx.compose.ui.res.stringArrayResource(R.array.flows_how).toList()) }
            }
            itemsIndexed(rows) { _, row -> TreeRowView(row, onEditNode) }
        }
    }
}

@Composable
private fun TreeRowView(row: TreeRow, onEdit: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        repeat(row.depth) {
            Box(Modifier.width(18.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.via, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            val node = row.node
            when {
                row.missing != null -> IconLabel(
                    R.drawable.ic_error, stringResource(R.string.flow_missing_node, row.missing),
                    style = MaterialTheme.typography.bodySmall, tint = MaterialTheme.colorScheme.error,
                )
                row.repeatOf != null -> IconLabel(
                    R.drawable.ic_arrow_upward, stringResource(R.string.flow_back_to, row.repeatOf.title.ifBlank { row.repeatOf.id }),
                    style = MaterialTheme.typography.bodySmall, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                node != null -> NodeCard(node) { onEdit(node.id) }
            }
        }
    }
}

@Composable
private fun NodeCard(node: FlowNode, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(node.title.ifBlank { node.id }, style = MaterialTheme.typography.titleSmall)
            val first = node.messages.firstOrNull()
            Text(
                first?.text?.ifBlank { if (first.media != null) "[" + first.media.name + "]" else "" }.orEmpty(),
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val extras = buildList {
                if (node.messages.size > 1) add(stringResource(R.string.flow_n_messages, node.messages.size))
                if (node.delay > 0) add(stringResource(R.string.flow_delay_n, node.delay))
                if (node.after.type == FlowAfter.ASK) add(stringResource(R.string.flow_asks, node.after.variable))
            }
            if (extras.isNotEmpty()) Text(extras.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
        }
    }
}

// -- Editor de etapa ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NodeEditorScreen(botId: Long, nodeId: String?, onDone: () -> Unit) {
    val config = remember { ConfigRepository.load(botId) }
    val original = remember { config.flows.firstOrNull { it.id == nodeId } }
    val start = remember { original ?: FlowNode(title = "") }
    var title by remember { mutableStateOf(start.title) }
    var drafts by remember { mutableStateOf(start.messages.mapIndexed { i, m -> Draft(i.toLong(), m) }) }
    var nextKey by remember { mutableLongStateOf(start.messages.size.toLong()) }
    var edit by remember { mutableStateOf(start.edit) }
    var delay by remember { mutableStateOf(start.delay) }
    var afterType by remember { mutableStateOf(start.after.type) }
    var afterNode by remember { mutableStateOf(start.after.node) }
    var variable by remember { mutableStateOf(start.after.variable) }
    var validate by remember { mutableStateOf(start.after.validate) }
    var invalid by remember { mutableStateOf(start.after.invalid) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Etapas que os botões e o "depois" podem usar (inclui esta, para menus que voltam).
    val others = config.flows.filter { it.id != start.id } + start.copy(title = title.ifBlank { stringResource(R.string.flow_this_node) })
    val nodeIds = others.map { it.id }.toSet()
    val referencedBy = config.flows.filter { it.id != start.id && start.id in it.links() } +
        config.commands.filter { it.flow == start.id }.map { FlowNode(id = "", title = "/" + it.triggers.firstOrNull().orEmpty()) }

    val errTitle = stringResource(R.string.flow_err_title)
    val errEmpty = stringResource(R.string.flow_err_empty)
    val errButtons = stringResource(R.string.flow_err_buttons)
    val errVar = stringResource(R.string.flow_err_variable)
    val errNext = stringResource(R.string.flow_err_next)

    fun save() {
        val msgs = drafts.map { it.msg }.filter { it.text.isNotBlank() || it.media != null }
        errors = buildList {
            if (title.isBlank()) add(errTitle)
            if (msgs.isEmpty()) add(errEmpty)
            if (msgs.any { m -> m.buttons.flatten().any { !ButtonTypes.isValid(it, nodeIds) } }) add(errButtons)
            if (afterType == FlowAfter.ASK && !FlowAfter.VAR_NAME.matches(variable)) add(errVar)
            if (afterType == FlowAfter.GOTO && afterNode.let { it == null || it !in nodeIds }) add(errNext)
        }
        if (errors.isNotEmpty()) return
        val node = start.copy(
            title = title.trim(), messages = msgs, edit = edit, delay = delay,
            after = FlowAfter(afterType, afterNode.takeIf { afterType != FlowAfter.NONE }, variable, validate, invalid.trim()),
        )
        val current = ConfigRepository.load(botId)
        val list = current.flows
        val updated = if (list.any { it.id == node.id }) list.map { if (it.id == node.id) node else it } else list + node
        ConfigRepository.save(botId, current.copy(flows = updated))
        onDone()
    }

    Scaffold(
        topBar = {
            AppBar(stringResource(if (original == null) R.string.flow_new_node else R.string.flow_edit_node), onDone) {
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
                OutlinedTextField(
                    value = title, onValueChange = { title = it.take(64) },
                    label = { Text(stringResource(R.string.flow_node_title)) },
                    supportingText = { Text(stringResource(R.string.flow_node_title_hint)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (referencedBy.isNotEmpty()) {
                item {
                    IconLabel(
                        R.drawable.ic_account_tree,
                        stringResource(R.string.flow_reached_from, referencedBy.joinToString(", ") { it.title.ifBlank { it.id } }),
                        style = MaterialTheme.typography.bodySmall, tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            item { SectionTitle(stringResource(R.string.cmd_messages_title)) }
            itemsIndexed(drafts, key = { _, d -> d.key }) { index, draft ->
                MessageEditor(
                    botId = botId,
                    index = index,
                    count = drafts.size,
                    draft = draft,
                    onChange = { m -> drafts = drafts.map { if (it.key == draft.key) it.copy(msg = m) else it } },
                    onMove = { delta ->
                        val list = drafts.toMutableList()
                        val target = index + delta
                        if (target in list.indices) {
                            list.add(target, list.removeAt(index))
                            drafts = list
                        }
                    },
                    onRemove = { drafts = drafts.filter { it.key != draft.key } },
                    nodes = others,
                    extraVariables = config.flowVariables() + setOfNotNull(variable.takeIf { afterType == FlowAfter.ASK }),
                )
            }
            item {
                OutlinedButton(onClick = { drafts = drafts + Draft(nextKey, BotMessage()); nextKey++ }, modifier = Modifier.fillMaxWidth()) {
                    ButtonLabel(R.drawable.ic_add, stringResource(R.string.cmd_add_message))
                }
            }

            item { SectionTitle(stringResource(R.string.flow_behavior)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        SwitchRow(stringResource(R.string.flow_edit), stringResource(R.string.flow_edit_sub), edit) { edit = it }
                        HorizontalDivider()
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.flow_delay), style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(0, 1, 2, 3, 5, 10).forEach { d ->
                                    FilterChip(
                                        selected = delay == d, onClick = { delay = d },
                                        label = { Text(if (d == 0) stringResource(R.string.flow_delay_none) else stringResource(R.string.flow_delay_n, d)) },
                                    )
                                }
                            }
                            Text(stringResource(R.string.flow_delay_sub), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.flow_after)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                FlowAfter.NONE to R.string.flow_after_none,
                                FlowAfter.GOTO to R.string.flow_after_goto,
                                FlowAfter.ASK to R.string.flow_after_ask,
                            ).forEach { (t, label) ->
                                FilterChip(selected = afterType == t, onClick = { afterType = t }, label = { Text(stringResource(label)) })
                            }
                        }
                        when (afterType) {
                            FlowAfter.GOTO -> {
                                Text(stringResource(R.string.flow_after_goto_sub), style = MaterialTheme.typography.bodySmall)
                                NodePicker(others.filter { it.id != start.id }, afterNode) { afterNode = it }
                            }
                            FlowAfter.ASK -> {
                                Text(stringResource(R.string.flow_after_ask_sub), style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(
                                    value = variable,
                                    onValueChange = { variable = it.lowercase().replace(' ', '_').filter { c -> c.isLetterOrDigit() || c == '_' }.take(32) },
                                    label = { Text(stringResource(R.string.flow_variable)) },
                                    supportingText = { Text(stringResource(R.string.flow_variable_hint, variable.ifBlank { "cidade" })) },
                                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                                )
                                Text(stringResource(R.string.flow_validate), style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(
                                        "text" to R.string.flow_validate_text, "number" to R.string.flow_validate_number,
                                        "email" to R.string.flow_validate_email, "phone" to R.string.flow_validate_phone,
                                    ).forEach { (v, label) ->
                                        FilterChip(selected = validate == v, onClick = { validate = v }, label = { Text(stringResource(label)) })
                                    }
                                }
                                OutlinedTextField(
                                    value = invalid, onValueChange = { invalid = it.take(500) },
                                    label = { Text(stringResource(R.string.flow_invalid)) },
                                    placeholder = { Text(stringResource(R.string.flow_invalid_hint)) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(stringResource(R.string.flow_ask_next), style = MaterialTheme.typography.titleSmall)
                                NodePicker(others.filter { it.id != start.id }, afterNode, allowNone = true) { afterNode = it }
                            }
                            else -> Text(stringResource(R.string.flow_after_none_sub), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (errors.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp)) { errors.forEach { IconLabel(R.drawable.ic_error, it) } }
                    }
                }
            }
            item {
                Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { ButtonLabel(R.drawable.ic_check, stringResource(R.string.save)) }
            }
        }
    }

    if (confirmDelete && original != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.flow_delete_title, original.title.ifBlank { original.id })) },
            text = {
                Text(
                    if (referencedBy.isEmpty()) stringResource(R.string.flow_delete_msg)
                    else stringResource(R.string.flow_delete_msg_refs, referencedBy.joinToString(", ") { it.title.ifBlank { it.id } }),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val current = ConfigRepository.load(botId)
                    ConfigRepository.save(botId, current.copy(flows = current.flows.filter { it.id != original.id }))
                    confirmDelete = false
                    onDone()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodePicker(nodes: List<FlowNode>, selected: String?, allowNone: Boolean = false, onPick: (String?) -> Unit) {
    if (nodes.isEmpty()) {
        Text(stringResource(R.string.flow_no_other_nodes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (allowNone) FilterChip(selected = selected == null, onClick = { onPick(null) }, label = { Text(stringResource(R.string.flow_after_none)) })
        nodes.forEach { n ->
            FilterChip(selected = selected == n.id, onClick = { onPick(n.id) }, label = { Text(n.title.ifBlank { n.id }) },
                leadingIcon = { AppIcon(R.drawable.ic_account_tree, Modifier.size(16.dp)) })
        }
    }
    Spacer(Modifier.width(0.dp))
}
