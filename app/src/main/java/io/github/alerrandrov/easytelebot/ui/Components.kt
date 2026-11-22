package io.github.alerrandrov.easytelebot.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alerrandrov.easytelebot.R
import io.github.alerrandrov.easytelebot.data.BotMeta
import io.github.alerrandrov.easytelebot.data.BotRepository
import io.github.alerrandrov.easytelebot.engine.BotState
import io.github.alerrandrov.easytelebot.engine.BotStatus
import io.github.alerrandrov.easytelebot.engine.Explanation
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = actions,
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.back),
                    )
                }
            }
        },
    )
}

@Composable
fun BotAvatar(name: String, photoPath: String?, size: Dp = 56.dp, version: Long = 0) {
    val bitmap = remember(photoPath, version) {
        photoPath?.let { p -> File(p).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(1).uppercase().ifEmpty { "B" },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun BotAvatar(meta: BotMeta, size: Dp = 56.dp) =
    BotAvatar(meta.name, BotRepository.photoFile(meta.id).path, size, meta.photoVersion)

/** "Agora" que se atualiza sozinho, para textos como "Ligado há 5 min". */
@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(20_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

fun formatElapsed(context: Context, millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> context.getString(R.string.elapsed_now)
        minutes < 60 -> context.getString(R.string.elapsed_min, minutes)
        minutes < 24 * 60 -> context.getString(R.string.elapsed_hours, minutes / 60, minutes % 60)
        else -> context.getString(R.string.elapsed_days, minutes / (24 * 60), (minutes / 60) % 24)
    }
}

@Composable
fun statusLabel(status: BotStatus, now: Long): Pair<String, Color> {
    val context = LocalContext.current
    return when {
        !status.running && status.state == BotState.FATAL -> stringResource(R.string.state_fatal) to Color(0xFFD32F2F)
        !status.running -> stringResource(R.string.state_stopped) to Color(0xFF9E9E9E)
        status.state == BotState.RUNNING ->
            stringResource(R.string.state_running_for, formatElapsed(context, now - status.startedAt)) to Color(0xFF2E7D32)
        status.state == BotState.RETRYING -> stringResource(R.string.state_retrying) to Color(0xFFF9A825)
        status.state == BotState.FATAL -> stringResource(R.string.state_fatal) to Color(0xFFD32F2F)
        else -> stringResource(R.string.state_starting) to Color(0xFFF9A825)
    }
}

@Composable
fun StatusLine(status: BotStatus, now: Long) {
    val (label, color) = statusLabel(status, now)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** Erro explicado: o que aconteceu + passo a passo + botões de ação. */
@Composable
fun ExplanationCard(
    explanation: Explanation,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(explanation.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(explanation.message, style = MaterialTheme.typography.bodyMedium)
            StepList(explanation.steps)
            actions()
        }
    }
}

@Composable
fun StepList(steps: List<String>) {
    if (steps.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEachIndexed { i, step ->
            Row {
                Text("${i + 1}. ", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(step, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    icon: Int? = null,
    content: @Composable () -> Unit = {},
) {
    val inner: @Composable () -> Unit = {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { AppIcon(icon, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                content()
            }
        }
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) { inner() }
    } else {
        Card(modifier = Modifier.fillMaxWidth()) { inner() }
    }
}

/** Ícone vetorial do app (res/drawable/ic_*.xml, Material Icons). Decorativo: sem descrição. */
@Composable
fun AppIcon(res: Int, modifier: Modifier = Modifier.size(20.dp), tint: Color = LocalContentColor.current, description: String? = null) {
    Icon(painterResource(res), description, modifier, tint)
}

/** Ícone + texto na mesma linha (ex.: "relógio 14/11 10:22"). */
@Composable
fun IconLabel(
    icon: Int,
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    tint: Color = LocalContentColor.current,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(text, style = style)
    }
}

/** Conteúdo de botão: ícone + texto. */
@Composable
fun ButtonLabel(icon: Int, text: String) {
    AppIcon(icon, Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(text)
}

fun openUrl(context: Context, url: String) {
    safeStart(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

/** Abre uma tela do sistema sem derrubar o app se ela não existir neste aparelho. */
fun safeStart(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}
