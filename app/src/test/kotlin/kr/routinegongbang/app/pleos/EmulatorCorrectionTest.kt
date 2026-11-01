package kr.routinegongbang.app.pleos

import kr.routinegongbang.core.model.ValueCorrection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The seat sensors read inverted on the Pleos emulator's Virtual Console; nothing says a real
 * car does the same, so the catalog's inversion applies on the emulator only.
 */
class EmulatorCorrectionTest {

    @Test
    fun `the inversion applies on the emulator`() {
        assertEquals(ValueCorrection.INVERT_BOOLEAN, EmulatorCorrection.effective(ValueCorrection.INVERT_BOOLEAN, emulator = true))
    }

    @Test
    fun `a real car gets the values as the sdk reports them`() {
        assertEquals(ValueCorrection.NONE, EmulatorCorrection.effective(ValueCorrection.INVERT_BOOLEAN, emulator = false))
    }

    @Test
    fun `no correction stays none everywhere`() {
        assertEquals(ValueCorrection.NONE, EmulatorCorrection.effective(ValueCorrection.NONE, emulator = true))
        assertEquals(ValueCorrection.NONE, EmulatorCorrection.effective(ValueCorrection.NONE, emulator = false))
    }
}
