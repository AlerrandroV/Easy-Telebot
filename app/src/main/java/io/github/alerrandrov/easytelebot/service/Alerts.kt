package io.github.alerrandrov.easytelebot.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.alerrandrov.easytelebot.MainActivity
import io.github.alerrandrov.easytelebot.R

/** Avisos que pedem atenção do dono do bot (ex.: bloqueio automático do antispam). */
object Alerts {
    private const val CHANNEL_ID = "alerts"
    const val EXTRA_BOT = "open_bot"
    const val EXTRA_SCREEN = "open_screen"
    const val SCREEN_ANTISPAM = "antispam"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.alerts_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.alerts_channel_desc) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun allowed(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notificationId(botId: Long, userId: Long) = (31 * botId + userId).hashCode()

    /** "Fulano foi bloqueado por spam: 12 mensagens em 10 segundos. Toque para analisar." */
    fun spamBlocked(context: Context, botId: Long, botName: String, userId: Long, name: String, reason: String) {
        if (!allowed(context)) return
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_BOT, botId)
            .putExtra(EXTRA_SCREEN, SCREEN_ANTISPAM)
        val open = PendingIntent.getActivity(
            context, notificationId(botId, userId), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.alert_spam_title, botName))
            .setContentText(context.getString(R.string.alert_spam_text, name, reason))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.alert_spam_text, name, reason)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId(botId, userId), notification)
        } catch (e: SecurityException) {
            // permissão retirada entre a checagem e o aviso
        }
    }

    /** Caso resolvido (no Telegram ou no app): tira o aviso da barra. */
    fun dismissSpam(context: Context, botId: Long, userId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(botId, userId))
    }
}
