package io.github.alerrandrov.easytelebot

import android.app.Application
import io.github.alerrandrov.easytelebot.engine.BotRuntime
import io.github.alerrandrov.easytelebot.service.BotService

class EasyTelebotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BotRuntime.init(this)
        BotService.createChannel(this)
    }
}
