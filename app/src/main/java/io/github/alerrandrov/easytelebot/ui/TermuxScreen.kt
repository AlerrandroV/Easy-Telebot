package io.github.alerrandrov.easytelebot.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.ConfigRepository
import io.github.alerrandrov.easytelebot.data.TermuxSettings
import io.github.alerrandrov.easytelebot.engine.BotRuntime
import io.github.alerrandrov.easytelebot.engine.EngineError
import io.github.alerrandrov.easytelebot.engine.ErrorExplainer
import io.github.alerrandrov.easytelebot.termux.TermuxRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val ALLOW_EXTERNAL =
    "mkdir -p ~/.termux && echo \"allow-external-apps = true\" >> ~/.termux/termux.properties && termux-reload-settings"

/** Exemplos de script no idioma do app (`res/raw` = inglês, `res/raw-pt` = português). */
private fun sample(context: android.content.Context, id: Int): String =
    context.resources.openRawResource(id).bufferedReader().use { it.readText() }.trimEnd()

/**
 * Integração com o Termux: comandos que rodam scripts (qualquer linguagem) e a API
 * local do bot, que deixa bibliotecas de bot rodarem no Termux sem conhecer o token.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TermuxScreen(botId: Long, onNewScriptCommand: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(TermuxRunner.isInstalled(context)) }
    var permitted by remember { mutableStateOf(TermuxRunner.hasPermission(context)) }
    var config by remember { mutableStateOf(ConfigRepository.load(botId)) }
    val listening by BotRuntime.localApi.collectAsStateWithLifecycle()
    val statuses by BotRuntime.status.collectAsStateWithLifecycle()
    val running = statuses[botId]?.running == true
    var testing by remember { mutableStateOf(false) }
    var testOutput by remember { mutableStateOf<String?>(null) }
    var testError by remember { mutableStateOf<EngineError?>(null) }
    var portText by remember { mutableStateOf(config.termux.port.toString()) }
    var portError by remember { mutableStateOf<String?>(null) }
    val scripts = config.commands.filter { it.script != null }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permitted = ok || TermuxRunner.hasPermission(context)
    }
    LaunchedEffect(Unit) {
        installed = TermuxRunner.isInstalled(context)
        permitted = TermuxRunner.hasPermission(context)
    }

    fun saveTermux(change: (TermuxSettings) -> TermuxSettings) {
        val current = ConfigRepository.load(botId)
        config = current.copy(termux = change(current.termux))
        ConfigRepository.save(botId, config)
    }

    fun test() {
        testing = true
        testOutput = null
        testError = null
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                TermuxRunner.run(
                    context,
                    "echo \"Termux OK: \$(uname -m)\"; for p in python node bash ruby php go; do command -v \$p >/dev/null && echo \"- \$p: \$(command -v \$p)\"; done; true",
                    "{}", 20,
                )
            }
            testing = false
            val o = JSONObject(raw)
            if (o.optBoolean("ok")) testOutput = (o.optString("stdout") + o.optString("stderr")).trim()
            else testError = EngineError.fromJson(o.getJSONObject("error"))
        }
    }

    val errPort = stringResource(R.string.termux_port_invalid)
    val errPortTaken = stringResource(R.string.termux_port_taken)

    Scaffold(topBar = { AppBar(stringResource(R.string.termux_title), onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.termux_intro), style = MaterialTheme.typography.bodyMedium) }

            // 1. Preparar o Termux
            item { SectionTitle(stringResource(R.string.termux_setup)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CheckLine(installed, stringResource(if (installed) R.string.termux_installed else R.string.termux_not_installed))
                        if (!installed) {
                            StepList(stringArrayResource(R.array.termux_install_steps).toList())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { openUrl(context, "https://f-droid.org/packages/com.termux/") }) {
                                    ButtonLabel(R.drawable.ic_open_in_new, "F-Droid")
                                }
                                OutlinedButton(onClick = { openUrl(context, "https://github.com/termux/termux-app/releases") }) {
                                    ButtonLabel(R.drawable.ic_open_in_new, "GitHub")
                                }
                            }
                            TextButton(onClick = { installed = TermuxRunner.isInstalled(context) }) {
                                ButtonLabel(R.drawable.ic_refresh, stringResource(R.string.termux_check_again))
                            }
                        }
                        HorizontalDivider()
                        Text(stringResource(R.string.termux_external_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.termux_external_msg), style = MaterialTheme.typography.bodySmall)
                        CodeBlock(ALLOW_EXTERNAL)
                        HorizontalDivider()
                        CheckLine(permitted, stringResource(if (permitted) R.string.termux_permitted else R.string.termux_not_permitted))
                        if (!permitted && installed) {
                            Button(onClick = { permission.launch(TermuxRunner.PERMISSION) }) {
                                ButtonLabel(R.drawable.ic_key, stringResource(R.string.termux_allow))
                            }
                            Text(stringResource(R.string.termux_allow_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                        OutlinedButton(onClick = { test() }, enabled = installed && permitted && !testing) {
                            ButtonLabel(R.drawable.ic_terminal, stringResource(if (testing) R.string.termux_testing else R.string.termux_test))
                        }
                        testOutput?.let { CodeBlock(it, copy = false) }
                        testError?.let { ExplanationCard(ErrorExplainer.explain(context, it)) }
                    }
                }
            }

            // 2. Comandos de script
            item { SectionTitle(stringResource(R.string.termux_scripts)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.termux_scripts_msg), style = MaterialTheme.typography.bodyMedium)
                        if (scripts.isNotEmpty()) {
                            Text(
                                stringResource(R.string.termux_scripts_existing, scripts.joinToString(", ") { "/" + it.triggers.firstOrNull().orEmpty() }),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(onClick = onNewScriptCommand) { ButtonLabel(R.drawable.ic_add, stringResource(R.string.add_script_command)) }
                        Text(stringResource(R.string.termux_env_title), style = MaterialTheme.typography.titleSmall)
                        StepList(stringArrayResource(R.array.termux_env_vars).toList())
                        Text(stringResource(R.string.termux_output_title), style = MaterialTheme.typography.titleSmall)
                        StepList(stringArrayResource(R.array.termux_output_rules).toList())
                        Text(stringResource(R.string.termux_examples), style = MaterialTheme.typography.titleSmall)
                        val ctx = LocalContext.current
                        CodeBlock(remember { sample(ctx, R.raw.termux_sample_python) })
                        CodeBlock(remember { sample(ctx, R.raw.termux_sample_node) })
                        CodeBlock(remember { sample(ctx, R.raw.termux_sample_bash) })
                    }
                }
            }

            // 3. API local
            item { SectionTitle(stringResource(R.string.termux_api)) }
            item {
                val t = config.termux
                Card(
                    Modifier.fillMaxWidth(),
                    colors = if (t.apiEnabled) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    else CardDefaults.cardColors(),
                ) {
                    Column {
                        SwitchRow(stringResource(R.string.termux_api_enable), stringResource(R.string.termux_api_enable_sub), t.apiEnabled) { on ->
                            saveTermux { it.copy(apiEnabled = on, key = it.key.ifEmpty { TermuxSettings.newKey(botId) }) }
                        }
                        if (t.apiEnabled) {
                            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                val port = listening[botId]
                                CheckLine(
                                    port != null,
                                    when {
                                        port != null -> stringResource(R.string.termux_api_listening, port)
                                        running -> stringResource(R.string.termux_api_starting)
                                        else -> stringResource(R.string.termux_api_bot_off)
                                    },
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = portText,
                                        onValueChange = { portText = it.filter(Char::isDigit).take(5); portError = null },
                                        label = { Text(stringResource(R.string.termux_port)) },
                                        singleLine = true,
                                        isError = portError != null,
                                        supportingText = { portError?.let { Text(it) } },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (portText != t.port.toString()) {
                                        Spacer(Modifier.width(8.dp))
                                        Button(onClick = {
                                            val p = portText.toIntOrNull()
                                            val taken = BotRepository.bots.value.filter { it.id != botId }
                                                .any { ConfigRepository.load(it.id).termux.let { o -> o.apiEnabled && o.port == p } }
                                            portError = when {
                                                p == null || p !in 1024..65535 -> errPort
                                                taken -> errPortTaken
                                                else -> null
                                            }
                                            if (portError == null && p != null) saveTermux { it.copy(port = p) }
                                        }) { Text(stringResource(R.string.save)) }
                                    }
                                }
                                Text(stringResource(R.string.termux_forward), style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(
                                        selected = t.forward == TermuxSettings.FORWARD_UNHANDLED,
                                        onClick = { saveTermux { it.copy(forward = TermuxSettings.FORWARD_UNHANDLED) } },
                                        label = { Text(stringResource(R.string.termux_forward_unhandled)) },
                                    )
                                    FilterChip(
                                        selected = t.forward == TermuxSettings.FORWARD_ALL,
                                        onClick = { saveTermux { it.copy(forward = TermuxSettings.FORWARD_ALL) } },
                                        label = { Text(stringResource(R.string.termux_forward_all)) },
                                    )
                                }
                                Text(
                                    stringResource(if (t.forward == TermuxSettings.FORWARD_ALL) R.string.termux_forward_all_sub else R.string.termux_forward_unhandled_sub),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(stringResource(R.string.termux_key), style = MaterialTheme.typography.titleSmall)
                                CodeBlock(t.key)
                                TextButton(onClick = { saveTermux { it.copy(key = TermuxSettings.newKey(botId)) } }) {
                                    ButtonLabel(R.drawable.ic_refresh, stringResource(R.string.termux_key_new))
                                }
                                Text(stringResource(R.string.termux_key_hint), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val base = "http://127.0.0.1:${t.port}"
                                Text(stringResource(R.string.termux_libs), style = MaterialTheme.typography.titleSmall)
                                Text("python-telegram-bot", style = MaterialTheme.typography.labelLarge)
                                CodeBlock(
                                    "app = (Application.builder().token(\"${t.key}\")\n" +
                                        "       .base_url(\"$base/bot\")\n" +
                                        "       .base_file_url(\"$base/file/bot\").build())",
                                )
                                Text("aiogram 3", style = MaterialTheme.typography.labelLarge)
                                CodeBlock(
                                    "from aiogram.client.session.aiohttp import AiohttpSession\n" +
                                        "from aiogram.client.telegram import TelegramAPIServer\n" +
                                        "session = AiohttpSession(api=TelegramAPIServer.from_base(\"$base\", is_local=False))\n" +
                                        "bot = Bot(\"${t.key}\", session=session)",
                                )
                                Text("Telegraf (Node.js)", style = MaterialTheme.typography.labelLarge)
                                CodeBlock("const bot = new Telegraf(\"${t.key}\", { telegram: { apiRoot: \"$base\" } })")
                                Text("pyTelegramBotAPI", style = MaterialTheme.typography.labelLarge)
                                CodeBlock("telebot.apihelper.API_URL = \"$base/bot{0}/{1}\"\nbot = telebot.TeleBot(\"${t.key}\")")
                                Text("curl", style = MaterialTheme.typography.labelLarge)
                                CodeBlock("curl \"$base/bot${t.key}/sendMessage\" -d chat_id=${stringResource(R.string.termux_curl_your_id)} -d text=${stringResource(R.string.termux_curl_hi)}")
                                Text(stringResource(R.string.termux_api_notes), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item {
                Text(stringResource(R.string.termux_keep_alive), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CheckLine(ok: Boolean, text: String) {
    IconLabel(
        if (ok) R.drawable.ic_check_circle else R.drawable.ic_error,
        text,
        tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

/** Bloco de código com fonte monoespaçada e botão de copiar. */
@Composable
fun CodeBlock(code: String, copy: Boolean = true) {
    val context = LocalContext.current
    val copied = stringResource(R.string.copied)
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                softWrap = false,
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(10.dp),
            )
            if (copy) {
                IconButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("code", code))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                }) { AppIcon(R.drawable.ic_content_copy, Modifier.size(18.dp), description = stringResource(R.string.copy)) }
            }
        }
    }
}
