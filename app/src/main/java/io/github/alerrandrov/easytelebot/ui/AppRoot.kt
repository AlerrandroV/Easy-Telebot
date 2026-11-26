package io.github.alerrandrov.easytelebot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.Prefs
import io.github.alerrandrov.easytelebot.service.Alerts

enum class Screen { HOME, ADD_BOT, BATTERY, BOT, CHANGE_TOKEN, PROFILE, COMMAND, LOG, ADMINS, STORAGE, CHATS, CHAT, BROADCAST, ANTISPAM, SHOP, PRODUCT, TERMUX, FLOWS, NODE, LOGS_ALL, MODERATION }

/** Pedido vindo de fora (notificação) para abrir uma tela de um bot. */
data class OpenRequest(val botId: Long, val screen: String)

/** Navegação simples por estado: poucas telas, sem biblioteca extra. */
@Composable
fun AppRoot(openRequest: OpenRequest? = null, onOpenHandled: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = Prefs(context)
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var botId by rememberSaveable { mutableLongStateOf(0L) }
    /** Comando em edição (`null` = novo). */
    var commandId by rememberSaveable { mutableStateOf<String?>(null) }
    /** Novo comando já marcado como "de admin". */
    var newCommandIsAdmin by rememberSaveable { mutableStateOf(false) }
    /** Novo comando já como "comando de script" (Termux); para onde voltar do editor. */
    var newCommandIsScript by rememberSaveable { mutableStateOf(false) }
    /** Tipo do item novo: comando ou um gatilho automático (palavra-chave, evento...). */
    var newCommandKind by rememberSaveable { mutableStateOf(io.github.alerrandrov.easytelebot.data.TriggerKinds.COMMAND) }
    var commandReturn by rememberSaveable { mutableStateOf(Screen.BOT) }
    /** Etapa de fluxo em edição (`null` = nova) e para onde voltar. */
    var nodeId by rememberSaveable { mutableStateOf<String?>(null) }
    var nodeReturn by rememberSaveable { mutableStateOf(Screen.FLOWS) }
    /** Abrir a tela de admins já no fluxo de "adicionar". */
    var adminsAutoAdd by rememberSaveable { mutableStateOf(false) }
    var chatId by rememberSaveable { mutableLongStateOf(0L) }
    /** Para onde voltar da conversa: lista de conversas ou antispam. */
    var chatReturn by rememberSaveable { mutableStateOf(Screen.CHATS) }
    /** Produto em edição (`null` = novo) e se o novo é assinatura; para onde voltar. */
    var productId by rememberSaveable { mutableStateOf<String?>(null) }
    var productIsSubscription by rememberSaveable { mutableStateOf(false) }
    var productReturn by rememberSaveable { mutableStateOf(Screen.SHOP) }

    LaunchedEffect(openRequest) {
        val r = openRequest ?: return@LaunchedEffect
        val target = when (r.screen) {
            Alerts.SCREEN_ANTISPAM -> Screen.ANTISPAM
            Alerts.SCREEN_SHOP -> Screen.SHOP
            else -> null
        }
        if (target != null && BotRepository.bots.value.any { it.id == r.botId }) {
            botId = r.botId
            screen = target
        }
        onOpenHandled()
    }

    val goHome = { screen = Screen.HOME }
    val goBot = { screen = Screen.BOT }

    BackHandler(enabled = screen != Screen.HOME) {
        screen = when (screen) {
            Screen.CHAT -> chatReturn
            Screen.PRODUCT -> productReturn
            Screen.COMMAND -> commandReturn
            Screen.NODE -> nodeReturn
            Screen.FLOWS -> if (commandId != null) Screen.COMMAND else Screen.BOT
            Screen.CHATS, Screen.BROADCAST, Screen.ANTISPAM, Screen.MODERATION, Screen.SHOP, Screen.TERMUX,
            Screen.CHANGE_TOKEN, Screen.PROFILE, Screen.LOG, Screen.ADMINS, Screen.STORAGE -> Screen.BOT
            else -> Screen.HOME
        }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            onAddBot = { screen = Screen.ADD_BOT },
            onOpenBot = { botId = it; screen = Screen.BOT },
            onBattery = { screen = Screen.BATTERY },
            onLogs = { screen = Screen.LOGS_ALL },
        )
        Screen.LOGS_ALL -> LogScreen(botId = null, onBack = goHome)
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
            onEditCommand = { id, admin ->
                commandId = id
                newCommandIsAdmin = admin
                newCommandIsScript = false
                newCommandKind = io.github.alerrandrov.easytelebot.data.TriggerKinds.COMMAND
                commandReturn = Screen.BOT
                screen = Screen.COMMAND
            },
            onNewScriptCommand = {
                commandId = null
                newCommandIsAdmin = false
                newCommandIsScript = true
                newCommandKind = io.github.alerrandrov.easytelebot.data.TriggerKinds.COMMAND
                commandReturn = Screen.BOT
                screen = Screen.COMMAND
            },
            onNewTrigger = { kind ->
                commandId = null
                newCommandIsAdmin = false
                newCommandIsScript = false
                newCommandKind = kind
                commandReturn = Screen.BOT
                screen = Screen.COMMAND
            },
            onTermux = { screen = Screen.TERMUX },

            onProfile = { screen = Screen.PROFILE },
            onLog = { screen = Screen.LOG },
            onAdmins = { add -> adminsAutoAdd = add; screen = Screen.ADMINS },
            onStorage = { screen = Screen.STORAGE },
            onChats = { screen = Screen.CHATS },
            onBroadcast = { screen = Screen.BROADCAST },
            onAntispam = { screen = Screen.ANTISPAM },
            onModeration = { screen = Screen.MODERATION },
            onShop = { screen = Screen.SHOP },
            onNewProduct = { sub ->
                productId = null
                productIsSubscription = sub
                productReturn = Screen.BOT
                screen = Screen.PRODUCT
            },
            onChangeToken = { screen = Screen.CHANGE_TOKEN },
            onBattery = { screen = Screen.BATTERY },
        )
        Screen.CHANGE_TOKEN -> TokenScreen(expectedBotId = botId, onBack = goBot, onSaved = { goBot() })
        Screen.PROFILE -> ProfileScreen(botId = botId, onBack = goBot)
        Screen.COMMAND -> CommandEditorScreen(
            botId = botId,
            commandId = commandId,
            newAsAdmin = newCommandIsAdmin,
            newAsScript = newCommandIsScript,
            newKind = newCommandKind,
            onOpenFlows = { screen = Screen.FLOWS },
            onDone = { screen = commandReturn },
        )
        Screen.FLOWS -> FlowsScreen(
            botId = botId,
            onEditNode = { nodeId = it; nodeReturn = Screen.FLOWS; screen = Screen.NODE },
            onBack = { screen = if (commandId != null) Screen.COMMAND else Screen.BOT },
        )
        Screen.NODE -> NodeEditorScreen(botId = botId, nodeId = nodeId, onDone = { screen = nodeReturn })
        Screen.TERMUX -> TermuxScreen(
            botId = botId,
            onNewScriptCommand = {
                commandId = null
                newCommandIsAdmin = false
                newCommandIsScript = true
                commandReturn = Screen.TERMUX
                screen = Screen.COMMAND
            },
            onBack = goBot,
        )
        Screen.LOG -> LogScreen(botId = botId, onBack = goBot)
        Screen.STORAGE -> StorageScreen(botId = botId, onBack = goBot)
        Screen.BROADCAST -> BroadcastScreen(botId = botId, onBack = goBot)
        Screen.CHATS -> ChatsScreen(botId = botId, onOpenChat = { chatId = it; chatReturn = Screen.CHATS; screen = Screen.CHAT }, onBack = goBot)
        Screen.CHAT -> ChatScreen(botId = botId, chatId = chatId, onBack = { screen = chatReturn })
        Screen.SHOP -> ShopScreen(
            botId = botId,
            onEditProduct = { id, sub ->
                productId = id
                productIsSubscription = sub
                productReturn = Screen.SHOP
                screen = Screen.PRODUCT
            },
            onOpenChat = { chatId = it; chatReturn = Screen.SHOP; screen = Screen.CHAT },
            onBack = goBot,
        )
        Screen.PRODUCT -> ProductEditorScreen(
            botId = botId,
            productId = productId,
            subscription = productIsSubscription,
            onDone = { screen = productReturn },
        )
        Screen.MODERATION -> ModerationScreen(botId = botId, onBack = goBot)
        Screen.ANTISPAM -> AntispamScreen(
            botId = botId,
            onOpenChat = { chatId = it; chatReturn = Screen.ANTISPAM; screen = Screen.CHAT },
            onBack = goBot,
        )
        Screen.ADMINS -> AdminsScreen(botId = botId, autoAdd = adminsAutoAdd, onBack = goBot)
    }
}
