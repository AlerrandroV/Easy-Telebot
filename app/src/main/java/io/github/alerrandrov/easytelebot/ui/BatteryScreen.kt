package io.github.alerrandrov.easytelebot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.service.BatteryHelper

@Composable
fun BatteryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val ignoring = remember(tick) { BatteryHelper.isIgnoringOptimizations(context) }
    val guide = remember { BatteryHelper.guideForThisPhone() }

    Scaffold(contentWindowInsets = screenInsets, topBar = { AppBar(stringResource(R.string.battery_title), onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.battery_intro), style = MaterialTheme.typography.bodyLarge)

            // Passo 1: isenção de otimização de bateria (Doze).
            Card(
                Modifier.fillMaxWidth(),
                colors = if (ignoring) CardDefaults.cardColors() else CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.battery_step1_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (ignoring) R.string.battery_step1_ok else R.string.battery_step1_msg))
                    if (!ignoring) {
                        Button(onClick = {
                            if (!safeStart(context, BatteryHelper.requestIgnoreOptimizationsIntent(context))) {
                                safeStart(context, BatteryHelper.appDetailsIntent(context))
                            }
                        }) { Text(stringResource(R.string.battery_step1_button)) }
                    }
                }
            }

            // Passo 2: economizador da fabricante.
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.battery_step2_title, guide.brandName),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringResource(R.string.battery_step2_msg))
                    StepList(stringArrayResource(guide.stepsRes).toList())
                    Button(onClick = { safeStart(context, BatteryHelper.appDetailsIntent(context)) }) {
                        Text(stringResource(R.string.battery_open_app_settings))
                    }
                    OutlinedButton(onClick = { safeStart(context, BatteryHelper.dontKillMyAppIntent(guide)) }) {
                        Text(stringResource(R.string.battery_more_info))
                    }
                }
            }

            Text(
                stringResource(R.string.battery_test_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.done)) }
        }
    }
}
