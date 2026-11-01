package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.SdkModule
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilitySnapshotTest {

    private val catalog = SignalCatalog.default()
    private val heat = catalog.getOrThrow(SignalIds.STEERING_WHEEL_HEAT)

    @Test
    fun `closed control sdk blocks writes with SDK_NOT_OPEN`() {
        val snapshot = CapabilitySnapshot(openSdks = setOf(SdkModule.VEHICLE_STATE), access = mapOf(heat.id to Access.READ_WRITE))
        assertEquals(Reason.SDK_NOT_OPEN, snapshot.writeBlocker(heat))
        assertFalse(snapshot.canWrite(heat))
    }

    @Test
    fun `read-only access blocks writes with NO_PERMISSION`() {
        val snapshot = CapabilitySnapshot(openSdks = SdkModule.entries.toSet(), access = mapOf(heat.id to Access.READ))
        assertEquals(Reason.NO_PERMISSION, snapshot.writeBlocker(heat))
    }

    @Test
    fun `missing access entry counts as no permission`() {
        val snapshot = CapabilitySnapshot(openSdks = SdkModule.entries.toSet(), access = emptyMap())
        assertEquals(Reason.NO_PERMISSION, snapshot.writeBlocker(heat))
    }

    @Test
    fun `open sdk and read-write access allows the write`() {
        val snapshot = CapabilitySnapshot(openSdks = SdkModule.entries.toSet(), access = mapOf(heat.id to Access.READ_WRITE))
        assertNull(snapshot.writeBlocker(heat))
        assertTrue(snapshot.canWrite(heat))
    }

    @Test
    fun `signals that are not writable by design are reported as unsupported`() {
        val soc = catalog.getOrThrow(SignalIds.EV_SOC)
        val snapshot = CapabilitySnapshot(openSdks = SdkModule.entries.toSet(), access = mapOf(soc.id to Access.READ_WRITE))
        assertEquals(Reason.UNSUPPORTED, snapshot.writeBlocker(soc))
    }

    @Test
    fun `simulated full control opens every writable signal and is marked as simulated`() {
        val snapshot = CapabilitySnapshot.simulated(catalog, controlOpen = true)
        assertTrue(snapshot.simulated)
        assertTrue(snapshot.canWrite(heat))
    }

    @Test
    fun `simulated closed control downgrades writes`() {
        val snapshot = CapabilitySnapshot.simulated(catalog, controlOpen = false)
        assertTrue(snapshot.simulated)
        assertEquals(Reason.SDK_NOT_OPEN, snapshot.writeBlocker(heat))
    }

    @Test
    fun `sdk availability is reported per module`() {
        val snapshot = CapabilitySnapshot(openSdks = setOf(SdkModule.NAVI), access = emptyMap())
        assertTrue(snapshot.isOpen(SdkModule.NAVI))
        assertFalse(snapshot.isOpen(SdkModule.STT))
    }
}
