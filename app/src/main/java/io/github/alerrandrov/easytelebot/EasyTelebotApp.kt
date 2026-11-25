package io.github.alerrandrov.easytelebot

import android.app.Application
import android.content.Context
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.data.ConfigRepository
import io.github.alerrandrov.easytelebot.engine.BotRuntime
import io.github.alerrandrov.easytelebot.service.Alerts
import io.github.alerrandrov.easytelebot.service.BotService
import io.github.alerrandrov.easytelebot.ui.AppLanguages

class EasyTelebotApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguages.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        BotRepository.init(this)
        ConfigRepository.init(this)
        BotRuntime.init(this)
        BotService.createChannel(this)
        Alerts.createChannel(this)
    }
}
