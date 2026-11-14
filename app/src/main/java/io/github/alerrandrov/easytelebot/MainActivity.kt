package io.github.alerrandrov.easytelebot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.alerrandrov.easytelebot.ui.AppRoot
import io.github.alerrandrov.easytelebot.ui.EasyTelebotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EasyTelebotTheme {
                AppRoot()
            }
        }
    }
}
