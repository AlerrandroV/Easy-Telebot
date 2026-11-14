package io.github.alerrandrov.easytelebot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.alerrandrov.easytelebot.data.Prefs

enum class Screen { HOME, ADD_BOT, BATTERY, BOT, CHANGE_TOKEN, PROFILE, COMMAND, LOG, ADMINS }

/** Navegação simples por estado: poucas telas, sem biblioteca extra. */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val prefs = Prefs(context)
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var botId by rememberSaveable { mutableLongStateOf(0L) }
    /** Comando em edição (`null` = novo). */
    var commandId by rememberSaveable { mutableStateOf<String?>(null) }
    /** Novo comando já marcado como "de admin". */
    var newCommandIsAdmin by rememberSaveable { mutableStateOf(false) }
    /** Abrir a tela de admins já no fluxo de "adicionar". */
    var adminsAutoAdd by rememberSaveable { mutableStateOf(false) }

    val goHome = { screen = Screen.HOME }
    val goBot = { screen = Screen.BOT }

    BackHandler(enabled = screen != Screen.HOME) {
        screen = when (screen) {
            Screen.CHANGE_TOKEN, Screen.PROFILE, Screen.COMMAND, Screen.LOG, Screen.ADMINS -> Screen.BOT
            else -> Screen.HOME
        }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            onAddBot = { screen = Screen.ADD_BOT },
            onOpenBot = { botId = it; screen = Screen.BOT },
            onBattery = { screen = Screen.BATTERY },
        )
        Screen.ADD_BOT -> TokenScreen(
            expectedBotId = null,
            onBack = goHome,
            onSaved = { id ->
                botId = id
                screen = if (prefs.batteryGuideSeen) Screen.BOT else Screen.BATTERY
            },
        )
        Screen.BATTERY -> BatteryScreen(onBack = {
            prefs.batteryGuideSeen = true
            screen = if (botId != 0L) Screen.BOT else Screen.HOME
        })
        Screen.BOT -> BotScreen(
            botId = botId,
            onBack = goHome,
            onEditCommand = { id, admin -> commandId = id; newCommandIsAdmin = admin; screen = Screen.COMMAND },
            onProfile = { screen = Screen.PROFILE },
            onLog = { screen = Screen.LOG },
            onAdmins = { add -> adminsAutoAdd = add; screen = Screen.ADMINS },
            onChangeToken = { screen = Screen.CHANGE_TOKEN },
            onBattery = { screen = Screen.BATTERY },
        )
        Screen.CHANGE_TOKEN -> TokenScreen(expectedBotId = botId, onBack = goBot, onSaved = { goBot() })
        Screen.PROFILE -> ProfileScreen(botId = botId, onBack = goBot)
        Screen.COMMAND -> CommandEditorScreen(botId = botId, commandId = commandId, newAsAdmin = newCommandIsAdmin, onDone = goBot)
        Screen.LOG -> LogScreen(botId = botId, onBack = goBot)
        Screen.ADMINS -> AdminsScreen(botId = botId, autoAdd = adminsAutoAdd, onBack = goBot)
    }
}
