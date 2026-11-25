package io.github.alerrandrov.easytelebot.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.MediaImporter
import io.github.alerrandrov.easytelebot.data.TokenStore
import io.github.alerrandrov.easytelebot.engine.EngineError
import io.github.alerrandrov.easytelebot.engine.ErrorExplainer
import io.github.alerrandrov.easytelebot.engine.PythonBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NAME_LIMIT = 64
private const val ABOUT_LIMIT = 120
private const val DESCRIPTION_LIMIT = 512

/** Nome, "sobre", descrição e foto do bot, alterados direto pela Bot API. */
@Composable
fun ProfileScreen(botId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bots by BotRepository.bots.collectAsStateWithLifecycle()
    val meta = bots.firstOrNull { it.id == botId } ?: return
    val token = remember { TokenStore.load(context, botId) }
    val photoPath = BotRepository.photoFile(botId).path

    var loading by remember { mutableStateOf(true) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf<EngineError?>(null) }
    var original by remember { mutableStateOf<PythonBridge.Profile?>(null) }
    var name by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveErrors by remember { mutableStateOf<List<EngineError>>(emptyList()) }
    var saved by remember { mutableStateOf(false) }
    var photoBusy by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf<EngineError?>(null) }

    LaunchedEffect(reloadTick) {
        if (token == null) {
            loadError = EngineError("invalid_token", "")
            loading = false
            return@LaunchedEffect
        }
        loading = true
        loadError = null
        when (val r = withContext(Dispatchers.IO) { PythonBridge.getProfile(context, token, photoPath) }) {
            is PythonBridge.Result.Ok -> {
                original = r.value
                name = r.value.name
                about = r.value.shortDescription
                description = r.value.description
                BotRepository.update(botId) { it.copy(name = r.value.name, photoVersion = it.photoVersion + 1) }
            }
            is PythonBridge.Result.Failed -> loadError = r.error
        }
        loading = false
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null || token == null) return@rememberLauncherForActivityResult
        photoBusy = true
        photoError = null
        scope.launch {
            val temp = withContext(Dispatchers.IO) { MediaImporter.toTemp(context, uri) }
            val r = if (temp == null) {
                PythonBridge.Result.Failed(EngineError("media_error", ""))
            } else {
                withContext(Dispatchers.IO) { PythonBridge.setPhoto(context, token, temp.path, photoPath) }
                    .also { temp.delete() }
            }
            when (r) {
                is PythonBridge.Result.Ok -> BotRepository.update(botId) { it.copy(photoVersion = it.photoVersion + 1) }
                is PythonBridge.Result.Failed -> photoError = r.error
            }
            photoBusy = false
        }
    }

    fun save() {
        val o = original ?: return
        if (token == null) return
        saving = true
        saved = false
        saveErrors = emptyList()
        scope.launch {
            val errs = mutableListOf<EngineError>()
            withContext(Dispatchers.IO) {
                if (name.trim() != o.name) {
                    when (val r = PythonBridge.setName(context, token, name.trim())) {
                        is PythonBridge.Result.Ok -> BotRepository.update(botId) { it.copy(name = name.trim()) }
                        is PythonBridge.Result.Failed -> errs += r.error
                    }
                }
                if (about != o.shortDescription) {
                    (PythonBridge.setShortDescription(context, token, about) as? PythonBridge.Result.Failed)?.let { errs += it.error }
                }
                if (description != o.description) {
                    (PythonBridge.setDescription(context, token, description) as? PythonBridge.Result.Failed)?.let { errs += it.error }
                }
            }
            saveErrors = errs
            saving = false
            if (errs.isEmpty()) {
                saved = true
                original = o.copy(name = name.trim(), shortDescription = about, description = description)
            }
        }
    }

    val changed = original?.let { name.trim() != it.name || about != it.shortDescription || description != it.description } ?: false

    Scaffold(contentWindowInsets = screenInsets, topBar = { AppBar(stringResource(R.string.bot_profile), onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val error = loadError
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.profile_loading))
                }
            } else if (error != null) {
                ExplanationCard(ErrorExplainer.explain(context, error)) {
                    Button(onClick = { reloadTick++ }) { Text(stringResource(R.string.try_again)) }
                }
            } else {

            // Foto
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    BotAvatar(meta, 80.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.profile_photo), style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = { photoPicker.launch("image/*") }, enabled = !photoBusy) {
                            if (photoBusy) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.profile_change_photo))
                        }
                    }
                }
            }
            photoError?.let { err ->
                ExplanationCard(ErrorExplainer.explain(context, err)) {
                    if (err.code == "photo_unsupported") {
                        Button(onClick = { openUrl(context, "https://t.me/BotFather") }) {
                            Text(stringResource(R.string.token_open_botfather))
                        }
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(NAME_LIMIT); saved = false },
                label = { Text(stringResource(R.string.profile_name)) },
                supportingText = { Text(stringResource(R.string.profile_name_hint) + "  ${name.length}/$NAME_LIMIT") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = about,
                onValueChange = { about = it.take(ABOUT_LIMIT); saved = false },
                label = { Text(stringResource(R.string.profile_about)) },
                supportingText = { Text(stringResource(R.string.profile_about_hint) + "  ${about.length}/$ABOUT_LIMIT") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(DESCRIPTION_LIMIT); saved = false },
                label = { Text(stringResource(R.string.profile_description)) },
                supportingText = {
                    Text(stringResource(R.string.profile_description_hint) + "  ${description.length}/$DESCRIPTION_LIMIT")
                },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(onClick = { save() }, enabled = changed && !saving && name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.profile_save))
            }
            if (saved) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Text(stringResource(R.string.profile_saved), Modifier.padding(16.dp))
                }
            }
            saveErrors.forEach { ExplanationCard(ErrorExplainer.explain(context, it)) }

            Text(
                stringResource(R.string.profile_username_note, meta.username),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
        }
    }
}
