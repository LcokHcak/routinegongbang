package kr.routinegongbang.app.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CrashRecorderTest {

    private val dir: File = Files.createTempDirectory("crash").toFile()
    private val file = File(dir, "crash.log")
    private val thread = Thread("engine")

    private class Previous : Thread.UncaughtExceptionHandler {
        var got: Throwable? = null
        override fun uncaughtException(t: Thread, e: Throwable) { got = e }
    }

    @Test
    fun `a crash is written down, then handed to the system as before`() {
        val previous = Previous()
        val boom = IllegalStateException("boom")
        CrashRecorder(file, previous, now = { 1_000L }).uncaughtException(thread, boom)
        val text = file.readText()
        assertTrue("IllegalStateException: boom" in text, text)
        assertTrue("engine" in text, text)
        assertSame(boom, previous.got)
    }

    @Test
    fun `the record stays small, keeping the newest crash`() {
        val recorder = CrashRecorder(file, Previous(), now = { 1_000L }, maxBytes = 2_000)
        repeat(20) { recorder.uncaughtException(thread, RuntimeException("crash $it")) }
        assertTrue(file.length() <= 2_000, "${file.length()} bytes")
        assertTrue("crash 19" in file.readText())
    }

    @Test
    fun `a record that cannot be written never swallows the crash`() {
        val previous = Previous()
        val unwritable = File(file, "not-a-dir/crash.log") // its parent is a file, so the write fails
        file.writeText("x")
        CrashRecorder(unwritable, previous, now = { 1_000L }).uncaughtException(thread, RuntimeException("boom"))
        assertEquals("boom", previous.got?.message)
    }

    @Test
    fun `the last record is read back once, then cleared`() {
        CrashRecorder(file, Previous(), now = { 1_000L }).uncaughtException(thread, RuntimeException("boom"))
        assertTrue(CrashRecorder.takeLast(file)!!.contains("boom"))
        assertEquals(null, CrashRecorder.takeLast(file))
    }

    @Test
    fun `the headline names the newest crash`() {
        val recorder = CrashRecorder(file, Previous(), now = { 1_000L })
        recorder.uncaughtException(thread, RuntimeException("old"))
        recorder.uncaughtException(thread, IllegalStateException("new"))
        assertEquals("IllegalStateException: new", CrashRecorder.headline(file.readText()))
    }

    @Test
    fun `two threads crashing together both keep their record`() {
        val recorder = CrashRecorder(file, Previous(), now = { 1_000L })
        val threads = (1..8).map { n -> Thread { recorder.uncaughtException(thread, RuntimeException("crash $n")) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val text = file.readText()
        (1..8).forEach { assertTrue("crash $it" in text, text) }
    }
}
