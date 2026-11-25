package io.github.alerrandrov.easytelebot.termux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Roda linhas de comando no Termux pelo intent RUN_COMMAND (recurso oficial do Termux
 * para apps externos) e espera o resultado (stdout, stderr, código de saída).
 *
 * Pré-requisitos (a tela "Termux e scripts" guia a pessoa):
 * - Termux instalado (F-Droid ou GitHub);
 * - `allow-external-apps = true` em `~/.termux/termux.properties`;
 * - permissão "Executar comandos no Termux" concedida a este app.
 *
 * O script roda em `bash -c` com as variáveis TELEBOT_* exportadas e um tempo limite
 * (`timeout`, do coreutils do Termux): código 124 = tempo esgotado.
 */
object TermuxRunner {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val HOME = "/data/data/com.termux/files/home"
    internal const val EXTRA_REQUEST = "io.github.alerrandrov.easytelebot.TERMUX_REQUEST"

    /**
     * Exporta os pares NOME VALOR recebidos como argumentos e roda o comando com tempo limite.
     * Arquivos do Python facilitado (`termux("cmd", arquivo)`) chegam pelo stdin, uma linha
     * `nome<TAB>base64` por arquivo, e são gravados em `$TELEBOT_FILES_DIR` (apagados no fim).
     */
    private const val WRAPPER =
        "while [ \$# -gt 1 ]; do export \"\$1=\$2\"; shift 2; done; " +
            "export TELEBOT_FILES_DIR=\"\${TMPDIR:-/data/data/com.termux/files/usr/tmp}/telebot\"; " +
            "names=''; if [ -n \"\$TELEBOT_HAS_FILES\" ]; then mkdir -p \"\$TELEBOT_FILES_DIR\"; " +
            "while IFS=\$'\\t' read -r n d; do case \"\$n\" in ''|*/*) continue;; esac; " +
            "printf '%s' \"\$d\" | base64 -d > \"\$TELEBOT_FILES_DIR/\$n\"; names=\"\$names \$n\"; done; fi; " +
            "timeout -k 3 \"\$TELEBOT_TIMEOUT\" bash -c \"\$TELEBOT_SCRIPT\" </dev/null; code=\$?; " +
            "for n in \$names; do rm -f \"\$TELEBOT_FILES_DIR/\$n\"; done; exit \$code"

    /** Limite do que passa pelo stdin do RUN_COMMAND (o Android limita o tamanho de um intent). */
    private const val MAX_FILES_BYTES = 700 * 1024
    private val FILE_NAME = Regex("^[A-Za-z0-9._-]{1,80}$")

    private val ENV_NAME = Regex("^[A-Z][A-Z0-9_]*$")
    private val ids = AtomicInteger(1000)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Bundle?>>()

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun error(code: String, detail: String = "") =
        JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("detail", detail)).toString()

    internal fun complete(id: Int, result: Bundle?) {
        pending[id]?.complete(result)
    }

    /** `[{"src": caminho no app, "name": nome no Termux}]` → linhas `nome<TAB>base64` para o stdin. */
    private fun filesStdin(raw: String): String? {
        if (raw.isBlank()) return null
        val arr = org.json.JSONArray(raw)
        var total = 0L
        val out = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name")
            val file = java.io.File(o.optString("src"))
            require(FILE_NAME.matches(name)) { "nome de arquivo inválido: $name" }
            total += file.length()
            require(total <= MAX_FILES_BYTES) { "arquivos grandes demais para o Termux (${total / 1024} KB)" }
            out.append(name).append('\t')
                .append(android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)).append('\n')
        }
        return out.toString().takeIf { it.isNotEmpty() }
    }

    /**
     * Roda `command` e bloqueia até o fim (chame fora da thread principal).
     * Devolve JSON: `{"ok": true, "stdout", "stderr", "exit_code"}` ou `{"ok": false, "error": {...}}`.
     */
    fun run(context: Context, command: String, envJson: String, timeoutSec: Int): String {
        val app = context.applicationContext
        if (!isInstalled(app)) return error("termux_missing")
        if (!hasPermission(app)) return error("termux_permission")

        val env = try { JSONObject(envJson) } catch (e: Exception) { JSONObject() }
        val stdin = try {
            filesStdin(env.optString("TELEBOT_FILES"))
        } catch (e: Exception) {
            return error("termux_failed", e.message ?: e.toString())
        }
        env.remove("TELEBOT_FILES")
        if (stdin != null) env.put("TELEBOT_HAS_FILES", "1")
        val args = mutableListOf("-c", WRAPPER, "telebot")
        env.keys().forEach { k ->
            if (ENV_NAME.matches(k)) { args += k; args += env.optString(k) }
        }
        args += listOf("TELEBOT_SCRIPT", command, "TELEBOT_TIMEOUT", timeoutSec.coerceIn(1, 300).toString())

        val id = ids.incrementAndGet()
        val future = CompletableFuture<Bundle?>()
        pending[id] = future
        try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            // Mutável de propósito: o Termux preenche o resultado nos extras (intent explícito, só para nós).
            val result = PendingIntent.getBroadcast(
                app, id, Intent(app, TermuxResultReceiver::class.java).putExtra(EXTRA_REQUEST, id), flags,
            )
            val intent = Intent()
                .setClassName(PACKAGE, SERVICE)
                .setAction(ACTION)
                .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args.toTypedArray())
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Easy Telebot")
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result)
            if (stdin != null) intent.putExtra("com.termux.RUN_COMMAND_STDIN", stdin)
            try {
                app.startService(intent)
            } catch (e: IllegalStateException) {
                // App em segundo plano (Android 8+): o Termux aceita como serviço em primeiro plano.
                ContextCompat.startForegroundService(app, intent)
            }
            val bundle = try {
                future.get(timeoutSec.toLong() + 20, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                return error("termux_no_answer")
            } ?: return error("termux_failed", "o Termux não devolveu resultado")
            val err = bundle.getInt("err", -1)
            if (err != -1) return error("termux_failed", bundle.getString("errmsg") ?: "erro $err")
            return JSONObject()
                .put("ok", true)
                .put("stdout", bundle.getString("stdout") ?: "")
                .put("stderr", bundle.getString("stderr") ?: "")
                .put("exit_code", bundle.getInt("exitCode", 0))
                .toString()
        } catch (e: SecurityException) {
            return error("termux_permission", e.message ?: "")
        } catch (e: Exception) {
            return error("termux_failed", e.message ?: e.toString())
        } finally {
            pending.remove(id)
        }
    }
}

/** Recebe o resultado do RUN_COMMAND (o Termux dispara o PendingIntent com o bundle "result"). */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(TermuxRunner.EXTRA_REQUEST, -1)
        @Suppress("DEPRECATION")
        TermuxRunner.complete(id, intent.getBundleExtra("result"))
    }
}
