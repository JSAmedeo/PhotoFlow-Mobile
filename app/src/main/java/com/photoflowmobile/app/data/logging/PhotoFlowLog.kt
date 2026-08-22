package com.photoflowmobile.app.data.logging

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drop-in replacement for `android.util.Log` that also writes to a rotating file on the device.
 *
 * Call sites are unchanged: each file does
 * `import com.photoflowmobile.app.data.logging.PhotoFlowLog as Log`
 * and every existing `Log.i(TAG, msg)` keeps working. Signatures mirror `android.util.Log`
 * exactly, including the Int return, so the alias is transparent.
 *
 * **Why this exists.** Logcat is a RAM ring buffer: 256 KiB by default on these handsets, reset
 * by a reboot, and `logcat -G` sizing resets with it. After the 2026-08-20 field test the device
 * had rebooted, so nothing from the session survived — the Room database showed the outcomes but
 * nothing showed the reasoning. A field test runs with no laptop attached, so post-hoc capture is
 * impossible by construction. This makes a session self-documenting.
 *
 * **Two constraints shape the design.**
 *
 * 1. *Never block the caller.* Logging happens on the single `PhotoFlow-USB` thread, where a
 *    synchronous file write would stall image polling — the problem FR-6 just fixed. Lines go to
 *    a bounded channel with `DROP_OLDEST`, drained by one writer coroutine on `Dispatchers.IO`.
 *    Under flood the oldest lines are lost rather than the poll loop stalling.
 * 2. *Filter by level.* The PTP poll loop logs at DEBUG every 500 ms; writing that to file for a
 *    day is unusable. DEBUG reaches the file only when [verbose] is set. INFO and above always do
 *    (when logging is enabled), which is a few hundred lines an hour in normal operation.
 *
 * Logcat output is never gated — it happens regardless of [enabled], so `adb logcat` behaves as
 * it always has during development.
 */
object PhotoFlowLog {

    private const val RETENTION_DAYS = 7
    private const val MAX_TOTAL_BYTES = 20L * 1024 * 1024
    private const val QUEUE_CAPACITY = 2048
    private const val DIR_NAME = "logs"

    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val lineTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var logDir: File? = null
    @Volatile var enabled: Boolean = true
    @Volatile var verbose: Boolean = false

    private val queue = Channel<String>(QUEUE_CAPACITY, BufferOverflow.DROP_OLDEST)

    /** Started once from PhotoFlowApplication.onCreate. Until then, only logcat receives lines. */
    fun init(context: Context, scope: CoroutineScope) {
        val dir = File(context.filesDir, DIR_NAME)
        dir.mkdirs()
        logDir = dir
        prune()
        scope.launch(Dispatchers.IO) {
            for (line in queue) {
                runCatching { currentFile()?.appendText(line) }
            }
        }
    }

    // ── android.util.Log-compatible surface ───────────────────────────────────

    fun d(tag: String, msg: String): Int {
        write('D', tag, msg, null, toFile = verbose)
        return Log.d(tag, msg)
    }

    fun i(tag: String, msg: String): Int {
        write('I', tag, msg, null, toFile = true)
        return Log.i(tag, msg)
    }

    fun w(tag: String, msg: String): Int {
        write('W', tag, msg, null, toFile = true)
        return Log.w(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable?): Int {
        write('W', tag, msg, tr, toFile = true)
        return Log.w(tag, msg, tr)
    }

    fun e(tag: String, msg: String): Int {
        write('E', tag, msg, null, toFile = true)
        return Log.e(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable?): Int {
        write('E', tag, msg, tr, toFile = true)
        return Log.e(tag, msg, tr)
    }

    // ── File side ─────────────────────────────────────────────────────────────

    private fun write(level: Char, tag: String, msg: String, tr: Throwable?, toFile: Boolean) {
        if (!toFile || !enabled || logDir == null) return
        val sb = StringBuilder()
        sb.append(lineTimeFormat.format(Date())).append(' ')
            .append(level).append(' ').append(tag).append(": ").append(msg).append('\n')
        if (tr != null) sb.append(stackTrace(tr))
        // trySend never blocks; on a full queue DROP_OLDEST discards the stalest line.
        queue.trySend(sb.toString())
    }

    private fun stackTrace(tr: Throwable): String {
        val sw = StringWriter()
        tr.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    /** Today's file, rotating and pruning when the date rolls over. */
    private fun currentFile(): File? {
        val dir = logDir ?: return null
        val f = File(dir, "photoflow-${fileNameFormat.format(Date())}.log")
        if (!f.exists()) {
            runCatching { f.createNewFile() }
            prune()
        }
        return f
    }

    private fun prune() {
        val dir = logDir ?: return
        val files = dir.listFiles { f -> f.name.startsWith("photoflow-") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.name } ?: return
        files.drop(RETENTION_DAYS).forEach { runCatching { it.delete() } }
        // Size backstop: a verbose tethering day can outgrow the day count on its own.
        var total = 0L
        files.take(RETENTION_DAYS).forEach { f ->
            total += f.length()
            if (total > MAX_TOTAL_BYTES) runCatching { f.delete() }
        }
    }

    /** Newest first. Used by the ConfigScreen export and the capture tooling. */
    fun logFiles(): List<File> =
        logDir?.listFiles { f -> f.name.startsWith("photoflow-") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.name } ?: emptyList()

    /**
     * Writes a crash synchronously, bypassing the queue.
     *
     * The process is about to die, so the writer coroutine will never drain — this is the one
     * place a blocking write on the caller's thread is correct.
     */
    fun logCrashSync(thread: Thread, tr: Throwable) {
        runCatching {
            val header = "\n${lineTimeFormat.format(Date())} E PhotoFlow/Crash: " +
                    "UNCAUGHT on thread '${thread.name}'\n"
            currentFile()?.appendText(header + stackTrace(tr))
        }
    }
}
