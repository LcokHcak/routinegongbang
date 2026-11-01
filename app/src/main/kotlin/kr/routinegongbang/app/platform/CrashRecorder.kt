package kr.routinegongbang.app.platform

import android.content.Context
import java.io.File
import java.time.Instant

/**
 * Writes an uncaught crash to a small local file, then hands it on unchanged, so Android still ends the process and
 * restarts the sticky service. The next start reads the record back into the engine log. Stays on the device and
 * keeps only the exception, a short message and the top of its stack; the message may quote app data (a parser
 * error quotes its input), so it is cut short and the full record reaches logcat in debug builds only.
 */
internal class CrashRecorder(
    private val file: File,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Int = MAX_BYTES,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        // Recording must never stand in the way of the crash itself.
        runCatching { record(thread, error) }
        // Android always installs a default handler; without one, end the process ourselves rather than let a
        // crashed thread die quietly while the rest runs on.
        previous?.uncaughtException(thread, error) ?: run {
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(CRASH_EXIT_CODE)
        }
    }

    /** One crash at a time: two threads crashing together must both be written, neither over the other. */
    @Synchronized
    private fun record(thread: Thread, error: Throwable) {
        val entry = buildString {
            append(SEPARATOR).append(Instant.ofEpochMilli(now())).append(" thread=").append(thread.name).append('\n')
            generateSequence(error) { it.cause }.take(MAX_CAUSES).forEachIndexed { index, cause ->
                if (index > 0) append("caused by ")
                append(cause.javaClass.simpleName).append(": ").append(cause.message.orEmpty().take(MAX_MESSAGE)).append('\n')
                cause.stackTrace.take(MAX_FRAMES).forEach { append("  at ").append(it).append('\n') }
            }
        }
        val old = if (file.exists()) file.readText() else ""
        file.writeText(newestThatFit(old + entry))
    }

    /** Drops the oldest records until the rest fits; a single record too long for the file is cut. */
    private fun newestThatFit(text: String): String {
        var records = text.split(SEPARATOR).filter { it.isNotEmpty() }.map { SEPARATOR + it }
        while (records.size > 1 && records.sumOf { it.utf8Size() } > maxBytes) records = records.drop(1)
        val kept = records.joinToString("")
        return if (kept.utf8Size() <= maxBytes) kept else kept.take(maxBytes / MAX_UTF8_BYTES_PER_CHAR)
    }

    private fun String.utf8Size() = toByteArray(Charsets.UTF_8).size

    companion object {
        private const val FILE = "crash.log"
        private const val SEPARATOR = "=== "
        private const val MAX_BYTES = 16_384
        private const val MAX_FRAMES = 12
        private const val MAX_CAUSES = 4
        private const val MAX_UTF8_BYTES_PER_CHAR = 3
        private const val MAX_MESSAGE = 200
        private const val CRASH_EXIT_CODE = 10

        /** Once per process; a second call (activity and service share the process) keeps the first. */
        @Synchronized
        fun install(context: Context) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashRecorder) return
            Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(file(context), current))
        }

        fun file(context: Context) = File(context.filesDir, FILE)

        /** The records left by earlier crashes, or null; cleared once read, so each is reported once. */
        fun takeLast(file: File): String? {
            if (!file.exists()) return null
            val text = runCatching { file.readText() }.getOrNull() ?: return null
            file.delete()
            return text.takeIf { it.isNotBlank() }
        }

        /** "IllegalStateException: boom" of the newest record, for a one-line log entry. */
        fun headline(records: String): String? =
            records.split(SEPARATOR).lastOrNull { it.isNotBlank() }?.lineSequence()?.drop(1)?.firstOrNull()
    }
}
