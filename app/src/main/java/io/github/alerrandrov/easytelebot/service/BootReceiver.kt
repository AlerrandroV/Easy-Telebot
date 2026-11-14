package io.github.alerrandrov.easytelebot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Liga os bots marcados com "iniciar com o celular" quando o aparelho termina de ligar. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        BotService.startAutoBots(context)
    }
}
