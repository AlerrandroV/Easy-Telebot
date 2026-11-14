package io.github.alerrandrov.easytelebot.engine

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

data class AudienceCounts(val users: Int, val groups: Int, val channels: Int)

data class BroadcastJob(
    /** running, done, cancelled, interrupted */
    val status: String,
    /** "app" ou "telegram" (/post) */
    val source: String,
    val total: Int,
    val sent: Int,
    val failed: Int,
    val unreachable: Int,
    val startedAt: Long,
    val finishedAt: Long,
) {
    val running get() = status == "running"
}

/** Ponte para `broadcast.py`. Tudo bloqueia: chame fora da thread principal. */
object BroadcastBridge {
    private fun module(context: Context) = run {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        Python.getInstance().getModule("telebot_engine.broadcast")
    }

    fun audience(context: Context, dir: String): AudienceCounts {
        val o = JSONObject(module(context).callAttr("audience_counts_json", dir).toString())
        return AudienceCounts(o.optInt("users"), o.optInt("groups"), o.optInt("channels"))
    }

    fun start(context: Context, token: String, dir: String, payload: JSONObject): PythonBridge.Result<Int> = try {
        val o = JSONObject(module(context).callAttr("start_app", token, dir, payload.toString()).toString())
        if (o.optBoolean("ok")) PythonBridge.Result.Ok(o.optInt("total"))
        else PythonBridge.Result.Failed(EngineError.fromJson(o.getJSONObject("error")))
    } catch (e: Exception) {
        PythonBridge.Result.Failed(EngineError("unknown", e.message ?: e.toString()))
    }

    fun status(context: Context, dir: String): BroadcastJob? {
        val raw = module(context).callAttr("job_status", dir).toString()
        if (raw == "null") return null
        val o = JSONObject(raw)
        return BroadcastJob(
            status = o.optString("status"),
            source = o.optString("source"),
            total = o.optInt("total"),
            sent = o.optInt("sent"),
            failed = o.optInt("failed"),
            unreachable = o.optInt("unreachable"),
            startedAt = o.optLong("started_at") * 1000,
            finishedAt = o.optLong("finished_at") * 1000,
        )
    }

    fun cancel(context: Context, dir: String) {
        module(context).callAttr("cancel", dir)
    }
}
