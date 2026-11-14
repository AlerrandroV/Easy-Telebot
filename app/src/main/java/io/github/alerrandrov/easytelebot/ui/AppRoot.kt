package io.github.alerrandrov.easytelebot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.alerrandrov.easytelebot.data.Prefs
import io.github.alerrandrov.easytelebot.data.TokenStore

enum class Screen { HOME, TOKEN, COMMANDS, LOG, BATTERY }

/** Navegação simples por estado: poucas telas, sem biblioteca extra. */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val prefs = Prefs(context)
    var hasToken by rememberSaveable { mutableStateOf(TokenStore.load(context) != null) }
    var screen by rememberSaveable { mutableStateOf(if (hasToken) Screen.HOME else Screen.TOKEN) }

    BackHandler(enabled = screen != Screen.HOME && hasToken) { screen = Screen.HOME }

    when (screen) {
        Screen.HOME -> HomeScreen(navigate = { screen = it })
        Screen.TOKEN -> TokenScreen(
            canGoBack = hasToken,
            onBack = { screen = Screen.HOME },
            onSaved = {
                hasToken = true
                screen = if (prefs.batteryGuideSeen) Screen.HOME else Screen.BATTERY
            },
        )
        Screen.COMMANDS -> CommandsScreen(onBack = { screen = Screen.HOME })
        Screen.LOG -> LogScreen(onBack = { screen = Screen.HOME })
        Screen.BATTERY -> BatteryScreen(onBack = {
            prefs.batteryGuideSeen = true
            screen = Screen.HOME
        })
    }
}
