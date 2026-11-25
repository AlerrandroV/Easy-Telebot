package io.github.alerrandrov.easytelebot.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.engine.PythonBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val MAX_CODE = 20000
private const val INDENT = "    "

/**
 * Editor do Python facilitado: fonte monoespaçada, recuo automático no Enter (com mais
 * 4 espaços depois de uma linha que termina em ":"), atalhos para quem digita no celular
 * e conferência automática (o motor Python explica os erros, com a linha).
 */
@Composable
fun CodeEditor(code: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    var field by remember { mutableStateOf(TextFieldValue(code)) }
    // O código pode mudar por fora (ex.: trocar regras por código): aí o cursor vai para o fim.
    val value = if (field.text == code) field else TextFieldValue(code, TextRange(code.length))
    var check by remember { mutableStateOf<PythonBridge.CodeCheck?>(null) }
    var help by remember { mutableStateOf(false) }

    LaunchedEffect(code) {
        delay(700)  // espera a pessoa parar de digitar
        check = withContext(Dispatchers.IO) { PythonBridge.checkCode(context, code) }
    }

    fun emit(v: TextFieldValue) {
        val limited = if (v.text.length > MAX_CODE) v.copy(text = v.text.take(MAX_CODE)) else v
        field = limited
        onChange(limited.text)
    }

    fun insert(snippet: String) {
        val sel = value.selection
        val text = value.text.replaceRange(sel.min, sel.max, snippet)
        // Cursor dentro dos parênteses/aspas, se houver: enviar_texto("|")
        val inside = snippet.indexOf("\"\"").takeIf { it >= 0 }?.plus(1) ?: snippet.indexOf("()").takeIf { it >= 0 }?.plus(1)
        emit(TextFieldValue(text, TextRange(sel.min + (inside ?: snippet.length))))
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextField(
            value = value,
            onValueChange = { new -> emit(autoIndent(value, new)) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            minLines = 6,
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { insert(INDENT) }, label = { Text(stringResource(R.string.code_indent)) },
                leadingIcon = { AppIcon(R.drawable.ic_format_indent_increase, Modifier.size(16.dp)) })
            stringArrayResource(R.array.code_snippets).forEach { snip ->
                AssistChip(onClick = { insert(snip) }, label = { Text(snip.lineSequence().first(), fontFamily = FontFamily.Monospace) })
            }
            AssistChip(onClick = { help = true }, label = { Text(stringResource(R.string.code_help)) },
                leadingIcon = { AppIcon(R.drawable.ic_help, Modifier.size(16.dp)) })
        }
        val result = check
        when {
            result == null -> {}
            result.ok -> Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_check, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(" " + stringResource(R.string.code_ok), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                result.errors.forEach { e ->
                    Row {
                        AppIcon(R.drawable.ic_error, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.error)
                        Text(" " + e.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            icon = { AppIcon(R.drawable.ic_code) },
            title = { Text(stringResource(R.string.block_code)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.code_help_body), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.code_help_reference), style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { help = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}

/** Enter digitado no fim de uma linha: repete o recuo dela (e soma 4 espaços depois de ":"). */
private fun autoIndent(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    val pos = new.selection.start
    val typedNewline = new.text.length == old.text.length + 1 && pos > 0 && new.text[pos - 1] == '\n' &&
        new.selection.collapsed && new.text.removeRange(pos - 1, pos) == old.text
    if (!typedNewline) return new
    val lineStart = new.text.lastIndexOf('\n', pos - 2) + 1
    val previous = new.text.substring(lineStart, pos - 1)
    var indent = previous.takeWhile { it == ' ' }
    if (previous.trimEnd().endsWith(":")) indent += INDENT
    if (indent.isEmpty()) return new
    return TextFieldValue(new.text.substring(0, pos) + indent + new.text.substring(pos), TextRange(pos + indent.length))
}
