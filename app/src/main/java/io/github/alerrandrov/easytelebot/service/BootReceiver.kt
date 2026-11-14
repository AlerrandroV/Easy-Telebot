package io.github.alerrandrov.easytelebot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.alerrandrov.easytelebot.data.Prefs
import io.github.alerrandrov.easytelebot.data.TokenStore

/** Liga o bot quando o celular termina de ligar (se a pessoa ativou a opção). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        val prefs = Prefs(context)
        if (!prefs.startOnBoot || TokenStore.load(context) == null) return
        BotService.start(context)
    }
}
