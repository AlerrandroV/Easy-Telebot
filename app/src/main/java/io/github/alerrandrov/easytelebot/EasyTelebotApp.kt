package io.github.alerrandrov.easytelebot

import android.app.Application
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.engine.BotRuntime
import io.github.alerrandrov.easytelebot.service.Alerts
import io.github.alerrandrov.easytelebot.service.BotService

class EasyTelebotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BotRepository.init(this)
        BotRuntime.init(this)
        BotService.createChannel(this)
        Alerts.createChannel(this)
    }
}
