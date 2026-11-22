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
    const val SCREEN_SHOP = "shop"

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

    private fun post(context: Context, id: Int, botId: Long, screen: String, title: String, text: String) {
        if (!allowed(context)) return
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_BOT, botId)
            .putExtra(EXTRA_SCREEN, screen)
        val open = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // permissão retirada entre a checagem e o aviso
        }
    }

    /** "Fulano foi bloqueado por spam: 12 mensagens em 10 segundos. Toque para analisar." */
    fun spamBlocked(context: Context, botId: Long, botName: String, userId: Long, name: String, reason: String) =
        post(
            context, notificationId(botId, userId), botId, SCREEN_ANTISPAM,
            context.getString(R.string.alert_spam_title, botName), context.getString(R.string.alert_spam_text, name, reason),
        )

    /** "Nova venda em Loja do Zé: R$ 19,90 de Ana." */
    fun sale(context: Context, botId: Long, botName: String, orderId: String, buyer: String, total: String, problems: Boolean) =
        post(
            context, (31 * botId + orderId.hashCode()).hashCode(), botId, SCREEN_SHOP,
            context.getString(R.string.alert_sale_title, botName),
            context.getString(if (problems) R.string.alert_sale_text_problem else R.string.alert_sale_text, total, buyer, orderId),
        )

    /** Caso resolvido (no Telegram ou no app): tira o aviso da barra. */
    fun dismissSpam(context: Context, botId: Long, userId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(botId, userId))
    }
}
