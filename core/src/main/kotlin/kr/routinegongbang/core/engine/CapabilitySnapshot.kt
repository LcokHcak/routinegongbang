package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.SdkModule
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalSpec

/** Mirrors the SDK capability access levels. */
enum class Access { NONE, READ, WRITE, READ_WRITE }

/**
 * What the platform actually allows right now: which SDK modules are open
 * and the per-signal access returned by capability checks. The only source of truth for "can we write".
 * [simulated] marks demo snapshots; the UI must label their results as "모의" (simulated).
 */
data class CapabilitySnapshot(
    val openSdks: Set<SdkModule>,
    val access: Map<SignalId, Access>,
    val simulated: Boolean = false,
) {
    fun isOpen(module: SdkModule): Boolean = module in openSdks

    /** Null when writing is allowed; otherwise the reason shown to the user. */
    fun writeBlocker(spec: SignalSpec): Reason? {
        val writeSdk = spec.writeSdk
        return when {
            !spec.writable || writeSdk == null -> Reason.UNSUPPORTED
            !isOpen(writeSdk) -> Reason.SDK_NOT_OPEN
            access[spec.id] != Access.WRITE && access[spec.id] != Access.READ_WRITE -> Reason.NO_PERMISSION
            else -> null
        }
    }

    fun canWrite(spec: SignalSpec): Boolean = writeBlocker(spec) == null

    companion object {
        /** Demo toggle: everything readable/writable, with Vehicle Control open or closed. */
        fun simulated(catalog: SignalCatalog, controlOpen: Boolean): CapabilitySnapshot {
            val all = SdkModule.entries.toSet()
            return CapabilitySnapshot(
                openSdks = if (controlOpen) all else all - SdkModule.VEHICLE_CONTROL,
                access = catalog.all.associate { it.id to Access.READ_WRITE },
                simulated = true,
            )
        }
    }
}
