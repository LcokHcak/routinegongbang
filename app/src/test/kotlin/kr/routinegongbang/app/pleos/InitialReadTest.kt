package kr.routinegongbang.app.pleos

import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SdkModule
import kr.routinegongbang.core.model.SignalValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Pleos service does not always send a new subscriber the current value (dual zone and auto
 * recirculation never arrived), so every binding also reads it once. A read answer is only a first value: once the
 * subscription has spoken, or after unsubscribing, it is stale and must never look like a change.
 */
class InitialReadTest {

    /** Stands in for an SDK listener: what the car calls with a raw value. */
    private class Listener(val emit: Emit) {
        fun updated(v: Int?) { v?.let { emit(SignalValue.of(it)) } }
    }

    private val seen = mutableListOf<SignalValue>()
    private val failures = mutableListOf<Exception>()
    private var registered: Listener? = null
    private var pendingRead: Listener? = null

    private fun subscribe(withRead: Boolean = true, read: ((Listener) -> Unit)? = null) = subscribeWithRead(
        emit = { seen += it },
        failed = { failures += it },
        listen = ::Listener,
        register = { registered = it },
        unregister = { if (registered === it) registered = null },
        read = read ?: if (withRead) { l -> pendingRead = l } else null,
    )

    @Test
    fun `the current value is read when the subscription stays silent`() {
        subscribe()
        pendingRead!!.updated(3)
        assertEquals(listOf(SignalValue.of(3)), seen)
    }

    @Test
    fun `a read answer that arrives after a live event is stale and dropped`() {
        subscribe()
        registered!!.updated(4)
        pendingRead!!.updated(3)
        assertEquals(listOf(SignalValue.of(4)), seen, "3 would look like the user turning the fan back down")
    }

    @Test
    fun `live events keep coming after the read`() {
        subscribe()
        pendingRead!!.updated(3)
        registered!!.updated(4)
        registered!!.updated(5)
        assertEquals(listOf(3, 4, 5).map { SignalValue.of(it) }, seen)
    }

    @Test
    fun `a read answer after unsubscribing is dropped`() {
        val unsubscribe = subscribe()
        val late = pendingRead!!
        unsubscribe()
        late.updated(3)
        assertTrue(seen.isEmpty())
        assertEquals(null, registered)
    }

    @Test
    fun `a value the car does not know is not reported`() {
        subscribe()
        pendingRead!!.updated(null)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a getter that throws is reported and the live listener can still be removed`() {
        // A throw escaping after register would leave the unsubscriber unkept and leak the listener.
        val unsubscribe = subscribe(read = { throw SecurityException("no permission") })
        assertEquals(listOf("no permission"), failures.map { it.message })
        unsubscribe()
        assertEquals(null, registered)
    }

    @Test
    fun `a live event already on its way when unsubscribing is dropped`() {
        val unsubscribe = subscribe()
        val live = registered!!
        unsubscribe()
        live.updated(4)
        assertTrue(seen.isEmpty(), "nothing reaches a released engine")
    }

    @Test
    fun `without a getter only the subscription reports`() {
        subscribe(withRead = false)
        assertEquals(null, pendingRead)
        registered!!.updated(2)
        assertEquals(listOf(SignalValue.of(2)), seen)
    }

    @Test
    fun `every Pleos binding reads its current value when it subscribes`() {
        val catalog = SignalCatalog.default()
        val missing = PleosBindings.all.filter { !it.reads }.map { it.signal.value }
        assertTrue(missing.isEmpty(), "no initial read: $missing")
        assertTrue(PleosBindings.all.all { catalog.getOrThrow(it.signal).readSdk == SdkModule.VEHICLE_STATE })
    }
}
