package kr.routinegongbang.app.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.routinegongbang.core.engine.EngineSnapshot
import kr.routinegongbang.core.engine.EngineSnapshotJson
import kr.routinegongbang.core.engine.EngineStore
import kr.routinegongbang.core.habit.HabitLog
import kr.routinegongbang.core.habit.HabitLogJson
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.RoutineJson
import java.io.File
import java.io.FileNotFoundException

/** Crash-safe text file in app storage: a write either fully replaces the old content or leaves it untouched. */
internal class TextFile(private val file: File) {
    private val atomic = AtomicFile(file)

    /** Null when the file does not exist yet. */
    fun read(): String? = try {
        atomic.readFully().toString(Charsets.UTF_8)
    } catch (e: FileNotFoundException) {
        null
    }

    fun write(text: String) {
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: java.io.IOException) {
            atomic.failWrite(out)
            throw e
        }
    }

    /** Keeps an unreadable file for diagnosis instead of overwriting it. */
    fun quarantine() {
        if (file.exists()) file.renameTo(File(file.parentFile, "${file.name}.broken-${System.currentTimeMillis()}"))
    }
}

/** Engine state (trip, counters, reminders). A missing file is a fresh start; a corrupt one fails the load. */
class FileEngineStore(context: Context, prefix: String = "") : EngineStore {
    private val file = TextFile(File(context.filesDir, prefix + "engine_state.json"))

    override suspend fun load(): EngineSnapshot = withContext(Dispatchers.IO) {
        val text = file.read() ?: return@withContext EngineSnapshot()
        EngineSnapshotJson.decode(text).getOrThrow()
    }

    override suspend fun save(snapshot: EngineSnapshot) = withContext(Dispatchers.IO) {
        file.write(EngineSnapshotJson.encode(snapshot))
    }
}

/** Habit log: disposable personal data; a corrupt file is quarantined and the log starts fresh. */
class FileHabitStore(context: Context, prefix: String = "") {
    private val file = TextFile(File(context.filesDir, prefix + "habit_log.json"))

    fun load(): HabitLog {
        val text = file.read() ?: return HabitLog()
        return HabitLogJson.decode(text).getOrElse {
            file.quarantine()
            HabitLog()
        }
    }

    fun save(log: HabitLog) = file.write(HabitLogJson.encode(log))
}

/**
 * The user's routines. Seeded once from `assets/routines_seed.json` (template drafts).
 * Entries that fail to decode are skipped and reported, never silently lost: the file is only rewritten on save.
 */
class RoutineRepository(private val context: Context, private val log: (String) -> Unit, prefix: String = "") {
    private val file = TextFile(File(context.filesDir, prefix + "routines.json"))

    fun load(): List<Routine> {
        val text = file.read() ?: seed().also { file.write(it) }
        val entries = RoutineJson.decodeList(text).getOrElse {
            log("routines.json unreadable (${it.message}); using the seed")
            file.quarantine()
            return decode(seed())
        }
        entries.filter { it.isFailure }.forEach { log("routine skipped: ${it.exceptionOrNull()?.message}") }
        return RoutineMigrations.apply(entries.mapNotNull { it.getOrNull() })
    }

    fun save(routines: List<Routine>) = file.write(RoutineJson.encodeList(routines))

    private fun decode(text: String): List<Routine> = RoutineJson.decodeList(text).getOrNull().orEmpty().mapNotNull { it.getOrNull() }

    private fun seed(): String = context.assets.open(SEED).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private companion object {
        const val SEED = "routines_seed.json"
    }
}
