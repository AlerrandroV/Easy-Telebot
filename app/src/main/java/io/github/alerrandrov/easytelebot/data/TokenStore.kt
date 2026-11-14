package io.github.alerrandrov.easytelebot.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda o token de cada bot criptografado com uma chave AES do Android Keystore.
 * A chave nunca sai do aparelho; o arquivo de preferências só tem o texto cifrado.
 */
object TokenStore {
    private const val KEY_ALIAS = "easytelebot_token_key"
    private const val PREFS = "secure"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, botId: Long, token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs(context).edit()
            .putString("iv_$botId", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data_$botId", Base64.encodeToString(data, Base64.NO_WRAP))
            .apply()
    }

    fun load(context: Context, botId: Long): String? = decrypt(context, "iv_$botId", "data_$botId")

    fun delete(context: Context, botId: Long) {
        prefs(context).edit().remove("iv_$botId").remove("data_$botId").apply()
    }

    /** Token da versão 0.1 (um bot só). Usado apenas na migração. */
    fun loadLegacy(context: Context): String? = decrypt(context, "token_iv", "token_data")

    fun clearLegacy(context: Context) {
        prefs(context).edit().remove("token_iv").remove("token_data").apply()
    }

    private fun decrypt(context: Context, ivKey: String, dataKey: String): String? {
        val p = prefs(context)
        val iv = p.getString(ivKey, null) ?: return null
        val data = p.getString(dataKey, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            // Chave perdida (ex.: dados restaurados em outro aparelho): pede o token de novo.
            null
        }
    }
}
