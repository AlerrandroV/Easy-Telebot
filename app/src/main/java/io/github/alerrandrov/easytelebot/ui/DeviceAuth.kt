package io.github.alerrandrov.easytelebot.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/** O celular tem bloqueio de tela (PIN, padrão, senha ou biometria)? */
fun isDeviceSecure(context: Context): Boolean =
    context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

/**
 * Pede o desbloqueio do aparelho com a janela nativa do Android:
 * - Android 11+: BiometricPrompt aceitando biometria forte OU PIN/padrão/senha;
 * - Android 8–10: a tela de confirmação de credencial do sistema.
 *
 * Devolve uma função `(título, subtítulo) -> Unit` que abre o pedido.
 * [onResult] recebe `true` se a pessoa confirmou.
 */
@Composable
fun rememberDeviceAuth(onResult: (Boolean) -> Unit): (String, String) -> Unit {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onResult)
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        callback(r.resultCode == Activity.RESULT_OK)
    }
    return remember(context) {
        { title: String, subtitle: String ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val prompt = BiometricPrompt.Builder(context)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    .build()
                prompt.authenticate(
                    CancellationSignal(),
                    context.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            callback(true)
                        }

                        // Erro definitivo (cancelado, sem bloqueio...). Digital que não bate não chega aqui:
                        // o próprio sistema deixa tentar de novo.
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            callback(false)
                        }
                    },
                )
            } else {
                @Suppress("DEPRECATION")
                val intent = context.getSystemService(KeyguardManager::class.java)
                    ?.createConfirmDeviceCredentialIntent(title, subtitle)
                if (intent == null) callback(false) else legacy.launch(intent)
            }
        }
    }
}
