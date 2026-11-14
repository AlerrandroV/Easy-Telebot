package io.github.alerrandrov.easytelebot.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File

/** Onde o bot guarda os dados (usuários, histórico, backup). Mesmos nomes do `storage/__init__.py`. */
enum class StorageType(val key: String) {
    INTERNAL("internal"),
    DEVICE("device"),
    FIREBASE_RTDB("firebase_rtdb"),
    FIRESTORE("firestore"),
    DYNAMODB("dynamodb");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: INTERNAL
    }
}

/** Parte não secreta da configuração (fica em `storage.json`). */
data class StorageSettings(
    val type: StorageType = StorageType.INTERNAL,
    val storeUsers: Boolean = true,
    val storeHistory: Boolean = false,
    val devicePath: String = "",
    val firebaseUrl: String = "",
    val firestoreDatabase: String = "(default)",
    val awsRegion: String = "us-east-1",
    val awsTable: String = "easytelebot",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type.key)
        .put("store_users", storeUsers)
        .put("store_history", storeHistory)
        .put("device_path", devicePath)
        .put("firebase_url", firebaseUrl)
        .put("firestore_database", firestoreDatabase)
        .put("aws_region", awsRegion)
        .put("aws_table", awsTable)

    companion object {
        fun fromJson(o: JSONObject) = StorageSettings(
            type = StorageType.from(o.optString("type")),
            storeUsers = o.optBoolean("store_users", true),
            storeHistory = o.optBoolean("store_history", false),
            devicePath = o.optString("device_path"),
            firebaseUrl = o.optString("firebase_url"),
            firestoreDatabase = o.optString("firestore_database", "(default)").ifEmpty { "(default)" },
            awsRegion = o.optString("aws_region", "us-east-1"),
            awsTable = o.optString("aws_table", "easytelebot"),
        )
    }
}

/** Credenciais: guardadas cifradas no Keystore, nunca no `storage.json`. */
data class StorageSecrets(
    val firebaseSecret: String = "",
    val serviceAccount: String = "",
    val awsAccessKey: String = "",
    val awsSecretKey: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("firebase_secret", firebaseSecret)
        .put("service_account", serviceAccount)
        .put("aws_access_key", awsAccessKey)
        .put("aws_secret_key", awsSecretKey)

    /** E-mail e projeto da conta de serviço (para mostrar na tela). */
    fun serviceAccountInfo(): Pair<String, String>? = try {
        JSONObject(serviceAccount).let { it.getString("client_email") to it.getString("project_id") }
    } catch (e: Exception) {
        null
    }

    companion object {
        fun fromJson(o: JSONObject) = StorageSecrets(
            firebaseSecret = o.optString("firebase_secret"),
            serviceAccount = o.optString("service_account"),
            awsAccessKey = o.optString("aws_access_key"),
            awsSecretKey = o.optString("aws_secret_key"),
        )
    }
}

object StorageRepository {
    fun secretName(botId: Long) = "storage_$botId"

    fun file(botId: Long) = File(BotRepository.dir(botId), "storage.json")

    fun load(botId: Long): StorageSettings = try {
        StorageSettings.fromJson(JSONObject(file(botId).readText()))
    } catch (e: Exception) {
        StorageSettings()
    }

    fun loadSecrets(context: Context, botId: Long): StorageSecrets =
        TokenStore.loadSecret(context, secretName(botId))?.let {
            try { StorageSecrets.fromJson(JSONObject(it)) } catch (e: Exception) { null }
        } ?: StorageSecrets()

    fun save(context: Context, botId: Long, settings: StorageSettings, secrets: StorageSecrets) {
        file(botId).apply { parentFile?.mkdirs() }.writeText(settings.toJson().toString(2))
        TokenStore.saveSecret(context, secretName(botId), secrets.toJson().toString())
    }

    /** Configuração completa (com segredos) para entregar ao motor Python, só em memória. */
    fun engineJson(settings: StorageSettings, secrets: StorageSecrets): String {
        val o = settings.toJson()
        val s = secrets.toJson()
        s.keys().forEach { k -> o.put(k, s.get(k)) }
        return o.toString()
    }

    fun engineJson(context: Context, botId: Long): String = engineJson(load(botId), loadSecrets(context, botId))

    fun defaultDevicePath(meta: BotMeta): String =
        File(Environment.getExternalStorageDirectory(), "EasyTelebot/${meta.username.ifEmpty { meta.id.toString() }}").path

    // --- acesso a todos os arquivos (pasta do aparelho) ---

    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** Android 11+: tela do sistema "Acesso a todos os arquivos" deste app. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    fun allFilesAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
}
