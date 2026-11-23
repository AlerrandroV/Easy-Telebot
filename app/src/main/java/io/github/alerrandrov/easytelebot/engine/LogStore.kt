package io.github.alerrandrov.easytelebot.engine

import io.github.alerrandrov.easytelebot.data.BotRepository
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Logs gravados em arquivo (`logs.jsonl` na pasta de cada bot), para não sumirem quando
 * o app fecha. Uma linha JSON por entrada; quando passa de [MAX_FILE] linhas, o arquivo
 * é reescrito só com as [KEEP] mais novas. A gravação é feita numa thread própria.
 */
object LogStore {
    const val KEEP = 3000
    private const val MAX_FILE = 4000
    private const val FILE = "logs.jsonl"
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "logs").apply { isDaemon = true } }
    private val lines = mutableMapOf<Long, Int>()

    private fun file(id: Long) = File(BotRepository.dir(id), FILE)

    private fun toJson(e: LogEntry): String = JSONObject()
        .put("t", e.time)
        .put("l", e.level.name)
        .put("x", e.text)
        .apply {
            e.error?.let { err ->
                put("e", JSONObject()
                    .put("code", err.code).put("detail", err.detail)
                    .put("retry_after", err.retryAfter ?: 0).put("retry_in", err.retryIn ?: 0)
                    .put("context", err.context ?: "").put("fatal", err.fatal))
            }
        }.toString()

    private fun fromJson(line: String): LogEntry? = try {
        val o = JSONObject(line)
        LogEntry(
            time = o.getLong("t"),
            level = LogEntry.Level.valueOf(o.optString("l", "INFO")),
            text = o.optString("x"),
            error = o.optJSONObject("e")?.let { EngineError.fromJson(it) },
        )
    } catch (e: Exception) {
        null
    }

    /** Entradas gravadas, da mais nova para a mais antiga. */
    fun load(id: Long): List<LogEntry> {
        val f = file(id)
        if (!f.exists()) return emptyList()
        return try {
            val all = f.readLines()
            synchronized(lines) { lines[id] = all.size }
            all.asReversed().asSequence().mapNotNull(::fromJson).take(KEEP).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun append(id: Long, entry: LogEntry) {
        val json = toJson(entry)
        writer.execute {
            try {
                val f = file(id).apply { parentFile?.mkdirs() }
                f.appendText(json + "\n")
                val count = synchronized(lines) { (lines[id] ?: 0) + 1 }.also { synchronized(lines) { lines[id] = it } }
                if (count > MAX_FILE) {
                    val keep = f.readLines().takeLast(KEEP)
                    val tmp = File(f.parentFile, "$FILE.tmp")
                    tmp.writeText(keep.joinToString("\n", postfix = "\n"))
                    if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
                    synchronized(lines) { lines[id] = keep.size }
                }
            } catch (e: Exception) {
                // log é ajuda, nunca motivo para derrubar o app
            }
        }
    }

    fun clear(id: Long) {
        writer.execute {
            file(id).delete()
            synchronized(lines) { lines.remove(id) }
        }
    }
}
