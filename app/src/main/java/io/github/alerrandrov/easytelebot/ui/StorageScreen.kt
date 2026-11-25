package io.github.alerrandrov.easytelebot.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.StorageRepository
import io.github.alerrandrov.easytelebot.data.StorageSecrets
import io.github.alerrandrov.easytelebot.data.StorageSettings
import io.github.alerrandrov.easytelebot.data.StorageType
import io.github.alerrandrov.easytelebot.engine.BotRuntime
import io.github.alerrandrov.easytelebot.engine.EngineError
import io.github.alerrandrov.easytelebot.engine.ErrorExplainer
import io.github.alerrandrov.easytelebot.engine.PythonBridge
import io.github.alerrandrov.easytelebot.service.BotService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Resultado de uma ação (teste, backup...) mostrado num card. */
private sealed interface ActionResult {
    data class Ok(val message: String) : ActionResult
    data class Failed(val error: EngineError) : ActionResult
}

@Composable
fun StorageScreen(botId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bots by BotRepository.bots.collectAsStateWithLifecycle()
    val meta = bots.firstOrNull { it.id == botId } ?: return
    val dir = remember { BotRepository.dir(botId).path }

    var savedSettings by remember { mutableStateOf(StorageRepository.load(botId)) }
    var savedSecrets by remember { mutableStateOf(StorageRepository.loadSecrets(context, botId)) }
    var settings by remember {
        mutableStateOf(savedSettings.let { if (it.devicePath.isEmpty()) it.copy(devicePath = StorageRepository.defaultDevicePath(meta)) else it })
    }
    var secrets by remember { mutableStateOf(savedSecrets) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ActionResult?>(null) }
    var users by remember { mutableStateOf<List<JSONObject>?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val hasFiles = remember(resumeTick) { StorageRepository.hasAllFilesAccess(context) }

    val changed = settings != savedSettings || secrets != savedSecrets
    val currentJson = StorageRepository.engineJson(settings, secrets)
    val savedJson = StorageRepository.engineJson(savedSettings, savedSecrets)

    fun run(label: String, block: suspend () -> ActionResult) {
        busy = label
        result = null
        scope.launch {
            result = block()
            busy = null
        }
    }

    fun PythonBridge.Result<JSONObject>.toAction(ok: (JSONObject) -> String): ActionResult = when (this) {
        is PythonBridge.Result.Ok -> ActionResult.Ok(ok(value))
        is PythonBridge.Result.Failed -> ActionResult.Failed(error)
    }

    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }
    fun askFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!safeStart(context, StorageRepository.allFilesAccessIntent(context))) {
                safeStart(context, android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    val serviceAccountPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        }
        if (text == null) {
            Toast.makeText(context, context.getString(R.string.media_import_failed), Toast.LENGTH_LONG).show()
        } else {
            secrets = secrets.copy(serviceAccount = text.trim())
            if (secrets.serviceAccountInfo() == null) {
                result = ActionResult.Failed(EngineError("storage_config", context.getString(R.string.storage_sa_invalid)))
            }
        }
    }

    Scaffold(topBar = { AppBar(stringResource(R.string.storage_title), onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.storage_intro), style = MaterialTheme.typography.bodyMedium)

            // O que guardar
            Card(Modifier.fillMaxWidth()) {
                Column {
                    SwitchRow(stringResource(R.string.storage_users), stringResource(R.string.storage_users_sub), settings.storeUsers) {
                        settings = settings.copy(storeUsers = it)
                    }
                    HorizontalDivider()
                    SwitchRow(stringResource(R.string.storage_history), stringResource(R.string.storage_history_sub), settings.storeHistory) {
                        settings = settings.copy(storeHistory = it)
                    }
                }
            }

            SectionTitle(stringResource(R.string.storage_where))
            Card(Modifier.fillMaxWidth()) {
                Column {
                    StorageType.entries.forEachIndexed { i, type ->
                        if (i > 0) HorizontalDivider()
                        TypeRow(type, selected = settings.type == type) { settings = settings.copy(type = type) }
                    }
                }
            }

            // Campos do banco escolhido
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (settings.type) {
                        StorageType.INTERNAL -> Text(stringResource(R.string.storage_internal_info))
                        StorageType.DEVICE -> {
                            OutlinedTextField(
                                value = settings.devicePath,
                                onValueChange = { settings = settings.copy(devicePath = it) },
                                label = { Text(stringResource(R.string.storage_device_path)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (hasFiles) {
                                Text(stringResource(R.string.storage_files_ok), color = MaterialTheme.colorScheme.primary)
                            } else {
                                Text(stringResource(R.string.storage_files_needed))
                                Button(onClick = { askFilesAccess() }) { Text(stringResource(R.string.storage_files_grant)) }
                            }
                            Text(
                                stringResource(R.string.storage_device_info),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StorageType.FIREBASE_RTDB -> {
                            OutlinedTextField(
                                value = settings.firebaseUrl,
                                onValueChange = { settings = settings.copy(firebaseUrl = it.trim()) },
                                label = { Text(stringResource(R.string.storage_firebase_url)) },
                                placeholder = { Text(stringResource(R.string.storage_rtdb_placeholder)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SecretField(
                                value = secrets.firebaseSecret,
                                label = stringResource(R.string.storage_firebase_secret),
                            ) { secrets = secrets.copy(firebaseSecret = it.trim()) }
                            HelpSteps(stringResource(R.string.storage_help_title), stringArrayResource(R.array.storage_firebase_steps).toList())
                        }
                        StorageType.FIRESTORE -> {
                            val info = secrets.serviceAccountInfo()
                            if (info != null) {
                                Text(stringResource(R.string.storage_sa_loaded, info.second, info.first))
                            } else {
                                Text(stringResource(R.string.storage_sa_none))
                            }
                            OutlinedButton(onClick = { serviceAccountPicker.launch(arrayOf("application/json", "*/*")) }) {
                                Text(stringResource(if (info == null) R.string.storage_sa_pick else R.string.storage_sa_change))
                            }
                            OutlinedTextField(
                                value = settings.firestoreDatabase,
                                onValueChange = { settings = settings.copy(firestoreDatabase = it.trim()) },
                                label = { Text(stringResource(R.string.storage_firestore_db)) },
                                supportingText = { Text(stringResource(R.string.storage_firestore_db_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            HelpSteps(stringResource(R.string.storage_help_title), stringArrayResource(R.array.storage_firestore_steps).toList())
                        }
                        StorageType.DYNAMODB -> {
                            OutlinedTextField(
                                value = secrets.awsAccessKey,
                                onValueChange = { secrets = secrets.copy(awsAccessKey = it.trim()) },
                                label = { Text(stringResource(R.string.storage_aws_access)) },
                                placeholder = { Text("AKIA…") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SecretField(secrets.awsSecretKey, stringResource(R.string.storage_aws_secret)) {
                                secrets = secrets.copy(awsSecretKey = it.trim())
                            }
                            OutlinedTextField(
                                value = settings.awsRegion,
                                onValueChange = { settings = settings.copy(awsRegion = it.trim()) },
                                label = { Text(stringResource(R.string.storage_aws_region)) },
                                supportingText = { Text(stringResource(R.string.storage_aws_region_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = settings.awsTable,
                                onValueChange = { settings = settings.copy(awsTable = it.trim()) },
                                label = { Text(stringResource(R.string.storage_aws_table)) },
                                supportingText = { Text(stringResource(R.string.storage_aws_table_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            HelpSteps(stringResource(R.string.storage_help_title), stringArrayResource(R.array.storage_aws_steps).toList())
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        run("test") {
                            withContext(Dispatchers.IO) { PythonBridge.storageTest(context, currentJson, dir, botId) }
                                .toAction { r ->
                                    context.getString(
                                        if (r.optBoolean("table_created")) R.string.storage_test_ok_created else R.string.storage_test_ok
                                    )
                                }
                        }
                    },
                    enabled = busy == null,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.storage_test)) }
                Button(
                    onClick = {
                        StorageRepository.save(context, botId, settings, secrets)
                        savedSettings = settings
                        savedSecrets = secrets
                        BotService.restartIfRunning(context, botId)
                        Toast.makeText(context, context.getString(R.string.storage_saved), Toast.LENGTH_SHORT).show()
                    },
                    enabled = changed && busy == null,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.save)) }
            }

            busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.storage_working))
                }
            }
            when (val r = result) {
                is ActionResult.Ok -> Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) { Text(r.message, Modifier.padding(16.dp)) }
                is ActionResult.Failed -> {
                    val exp = ErrorExplainer.explain(context, r.error)
                    ExplanationCard(exp) {
                        if (r.error.code == "storage_permission") {
                            Button(onClick = { askFilesAccess() }) { Text(stringResource(R.string.storage_files_grant)) }
                        }
                    }
                }
                null -> {}
            }

            // Dados já salvos (usa a configuração salva)
            SectionTitle(stringResource(R.string.storage_data))
            if (changed) {
                Text(
                    stringResource(R.string.storage_save_first),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.storage_backup_info), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                run("backup") {
                                    withContext(Dispatchers.IO) { PythonBridge.storageBackup(context, savedJson, dir, botId) }
                                        .toAction { r ->
                                            context.getString(R.string.storage_backup_ok, r.optInt("commands"), r.optInt("admins"))
                                        }
                                }
                            },
                            enabled = busy == null && !changed,
                        ) { Text(stringResource(R.string.storage_backup)) }
                        OutlinedButton(onClick = { confirmRestore = true }, enabled = busy == null && !changed) {
                            Text(stringResource(R.string.storage_restore))
                        }
                    }
                    HorizontalDivider()
                    OutlinedButton(
                        onClick = {
                            busy = "users"
                            result = null
                            scope.launch {
                                when (val r = withContext(Dispatchers.IO) {
                                    PythonBridge.storageList(context, savedJson, dir, botId, "usuarios")
                                }) {
                                    is PythonBridge.Result.Ok -> {
                                        val arr = r.value.optJSONArray("items")
                                        users = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }
                                            .sortedByDescending { it.optLong("last_seen") }
                                    }
                                    is PythonBridge.Result.Failed -> result = ActionResult.Failed(r.error)
                                }
                                busy = null
                            }
                        },
                        enabled = busy == null && !changed,
                    ) { Text(stringResource(R.string.storage_view_users)) }
                }
            }
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.storage_restore_title)) },
            text = { Text(stringResource(R.string.storage_restore_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    run("restore") {
                        val r = withContext(Dispatchers.IO) { PythonBridge.storageRestore(context, savedJson, dir, botId) }
                        if (r is PythonBridge.Result.Ok) BotRuntime.requestReload(botId)
                        r.toAction { o ->
                            val at = DateFormat.getDateTimeInstance().format(Date(o.optLong("at") * 1000))
                            context.getString(R.string.storage_restore_ok, at, o.optInt("commands"), o.optInt("admins"))
                        }
                    }
                }) { Text(stringResource(R.string.storage_restore)) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    users?.let { list ->
        AlertDialog(
            onDismissRequest = { users = null },
            title = { Text(stringResource(R.string.storage_users_title, list.size)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (list.isEmpty()) Text(stringResource(R.string.storage_users_empty))
                    list.forEach { u ->
                        val name = listOf(u.optString("first_name"), u.optString("last_name")).filter { it.isNotBlank() }.joinToString(" ")
                        val handle = u.optString("username").takeIf { it.isNotBlank() }?.let { "@$it · " } ?: ""
                        Column {
                            Text(name.ifEmpty { "ID ${u.optLong("id")}" }, fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(
                                    R.string.storage_user_line,
                                    handle, u.optLong("id"), u.optInt("messages"),
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                        .format(Date(u.optLong("last_seen") * 1000)),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { users = null }) { Text(stringResource(R.string.done)) } },
        )
    }
}

@Composable
private fun TypeRow(type: StorageType, selected: Boolean, onClick: () -> Unit) {
    val (title, subtitle) = when (type) {
        StorageType.INTERNAL -> R.string.storage_type_internal to R.string.storage_type_internal_sub
        StorageType.DEVICE -> R.string.storage_type_device to R.string.storage_type_device_sub
        StorageType.FIREBASE_RTDB -> R.string.storage_type_firebase to R.string.storage_type_firebase_sub
        StorageType.FIRESTORE -> R.string.storage_type_firestore to R.string.storage_type_firestore_sub
        StorageType.DYNAMODB -> R.string.storage_type_dynamodb to R.string.storage_type_dynamodb_sub
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SecretField(value: String, label: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { show = !show }) { Text(stringResource(if (show) R.string.hide else R.string.show)) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HelpSteps(title: String, steps: List<String>) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        ButtonLabel(if (open) R.drawable.ic_expand_more else R.drawable.ic_chevron_right, title)
    }
    if (open) StepList(steps)
}

/** Nome curto do banco escolhido (para o card da tela do bot). */
@Composable
fun storageTypeLabel(type: StorageType): String = stringResource(
    when (type) {
        StorageType.INTERNAL -> R.string.storage_type_internal
        StorageType.DEVICE -> R.string.storage_type_device
        StorageType.FIREBASE_RTDB -> R.string.storage_type_firebase
        StorageType.FIRESTORE -> R.string.storage_type_firestore
        StorageType.DYNAMODB -> R.string.storage_type_dynamodb
    }
)
