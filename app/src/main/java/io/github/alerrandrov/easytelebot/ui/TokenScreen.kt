package io.github.alerrandrov.easytelebot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.Prefs
import io.github.alerrandrov.easytelebot.data.TokenStore
import io.github.alerrandrov.easytelebot.engine.ErrorExplainer
import io.github.alerrandrov.easytelebot.engine.PythonBridge
import io.github.alerrandrov.easytelebot.service.BotService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TokenScreen(canGoBack: Boolean, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<PythonBridge.CheckResult?>(null) }
    var showHelp by remember { mutableStateOf(!canGoBack) }

    fun check() {
        if (token.isBlank() || checking) return
        checking = true
        result = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    PythonBridge.checkToken(context, token.trim())
                } catch (e: Exception) {
                    PythonBridge.CheckResult.Failed(
                        io.github.alerrandrov.easytelebot.engine.EngineError("unknown", e.message ?: e.toString())
                    )
                }
            }
            result = r
            checking = false
        }
    }

    Scaffold(
        topBar = { AppBar(stringResource(R.string.token_title), onBack = if (canGoBack) onBack else null) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.token_intro), style = MaterialTheme.typography.bodyLarge)

            OutlinedTextField(
                value = token,
                onValueChange = { token = it.trim(); result = null },
                label = { Text(stringResource(R.string.token_label)) },
                placeholder = { Text("123456789:AAH...") },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    TextButton(onClick = { showToken = !showToken }) {
                        Text(stringResource(if (showToken) R.string.hide else R.string.show))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(onClick = { check() }, enabled = token.isNotBlank() && !checking, modifier = Modifier.fillMaxWidth()) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.token_checking))
                } else {
                    Text(stringResource(R.string.token_check))
                }
            }

            when (val r = result) {
                is PythonBridge.CheckResult.Ok -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BotAvatar(r.bot.firstName, r.bot.photoPath)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(r.bot.firstName, style = MaterialTheme.typography.titleMedium)
                                Text("@${r.bot.username}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text(stringResource(R.string.token_ok))
                        Button(
                            onClick = {
                                TokenStore.save(context, token.trim())
                                Prefs(context).apply {
                                    botName = r.bot.firstName
                                    botUsername = r.bot.username
                                    botPhoto = r.bot.photoPath
                                }
                                BotService.restartIfRunning(context)
                                onSaved()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.token_save)) }
                    }
                }
                is PythonBridge.CheckResult.Failed -> ExplanationCard(ErrorExplainer.explain(context, r.error))
                null -> {}
            }

            OutlinedButton(onClick = { showHelp = !showHelp }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.token_help_toggle))
            }
            if (showHelp) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.token_help_title), style = MaterialTheme.typography.titleMedium)
                        StepList(stringArrayResource(R.array.botfather_steps).toList())
                        OutlinedButton(onClick = { openUrl(context, "https://t.me/BotFather") }) { Text(stringResource(R.string.token_open_botfather)) }
                    }
                }
            }
            Text(
                stringResource(R.string.token_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
