package kr.routinegongbang.app.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The signal self-test writes to the car, so it runs on the emulator only. */
class DebugSettingsTest {

    @Test
    fun `only the Android emulator counts as an emulator`() {
        assertTrue(DebugSettings.isEmulator(hardware = "ranchu"))
        assertTrue(DebugSettings.isEmulator(hardware = "goldfish"))
        assertFalse(DebugSettings.isEmulator(hardware = "qcom"))
        assertFalse(DebugSettings.isEmulator(hardware = ""))
    }
}
