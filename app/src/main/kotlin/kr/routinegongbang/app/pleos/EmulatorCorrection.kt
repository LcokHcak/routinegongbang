package kr.routinegongbang.app.pleos

import kr.routinegongbang.core.model.ValueCorrection

/**
 * Catalog corrections were found on the Pleos emulator (seat sensors read inverted on the Virtual Console). A real
 * car is not known to share them, so it gets the values as the SDK reports them; the core
 * catalog keeps describing the emulator.
 */
internal object EmulatorCorrection {
    fun effective(correction: ValueCorrection, emulator: Boolean): ValueCorrection = when {
        emulator -> correction
        // Only the inversion is known to be an emulator artefact; a future correction is not dropped by default.
        correction == ValueCorrection.INVERT_BOOLEAN -> ValueCorrection.NONE
        else -> correction
    }
}
