package com.simorgh.mac.engine

import com.simorgh.mac.Paths
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The engine's own diary — the desktop port of Android's `service/EngineLog`:
 * what it tried, what answered and why things failed. Written to
 * `engine.log` in the data dir (rotated to `.1` past MAX_BYTES); the core's
 * own log is simorghd's `simorgh.log` next to it.
 */
object EngineLog {
    const val FILE = "engine.log"
    const val CORE_FILE = "simorgh.log"
    private const val MAX_BYTES = 256 * 1024L

    private val file: File get() = File(Paths.dataDir, FILE)
    private val time = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun i(message: String) = write('I', message, null)
    fun w(message: String, error: Throwable? = null) = write('W', message, error)
    fun e(message: String, error: Throwable? = null) = write('E', message, error)

    private fun write(level: Char, message: String, error: Throwable?) {
        println("[simorgh-$level] $message")
        val target = file
        val detail = error?.let { " (${it.javaClass.simpleName}: ${it.message})" }.orEmpty()
        val line = "${time.get()!!.format(Date())} $level $message$detail\n"
        synchronized(this) {
            runCatching {
                if (target.length() + line.length > MAX_BYTES) {
                    val old = File(target.parentFile, "$FILE.1")
                    old.delete()
                    target.renameTo(old)
                }
                target.appendText(line)
            }
        }
    }

    /** The last [maxBytes] of a log and its rotated predecessor, oldest first. */
    fun tail(dir: File, name: String, maxBytes: Int = 96 * 1024): String {
        val parts = listOf(File(dir, "$name.1"), File(dir, name)).filter { it.isFile }
        val out = StringBuilder()
        var budget = maxBytes.toLong()
        val chunks = ArrayList<String>()
        for (part in parts.reversed()) {
            if (budget <= 0) break
            runCatching {
                RandomAccessFile(part, "r").use { raf ->
                    val length = raf.length()
                    val take = minOf(length, budget)
                    raf.seek(length - take)
                    val bytes = ByteArray(take.toInt())
                    raf.readFully(bytes)
                    var text = String(bytes, Charsets.UTF_8)
                    if (take < length) text = text.substringAfter('\n', "")
                    chunks += text
                    budget -= take
                }
            }
        }
        chunks.reversed().forEach { out.append(it) }
        return out.toString()
    }

    /** Empty both logs (and their rotations). */
    fun clear(dir: File) {
        for (name in listOf(FILE, CORE_FILE)) {
            File(dir, "$name.1").delete()
            runCatching { RandomAccessFile(File(dir, name), "rw").use { it.setLength(0) } }
        }
    }
}

/** Constants the ported UI reads from the old `service.Engine` object. */
object EngineConst {
    const val REASON_CHOSEN_DOWN = "chosen_down"
    const val REASON_BLOCKED = "blocked"
}
