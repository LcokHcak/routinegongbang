package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.SdkModule
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue

internal val testCatalog = SignalCatalog.default()

internal val PARKED = DrivingState(gear = Gear.PARK, speedKph = 0.0, uxRestricted = false)
internal val DRIVING = DrivingState(gear = Gear.DRIVE, speedKph = 60.0, uxRestricted = true)

/** Everything open and writable, like the emulator. */
internal val FULL_CONTROL = CapabilitySnapshot(
    openSdks = SdkModule.entries.toSet(),
    access = testCatalog.all.associate { it.id to Access.READ_WRITE },
)

/** Real-car situation on 2026-10-27: Vehicle Control, TTS, STT, location are Coming Soon. */
internal val CONTROL_CLOSED = FULL_CONTROL.copy(
    openSdks = setOf(SdkModule.VEHICLE_STATE, SdkModule.NAVI, SdkModule.AAOS_CAR_PROPERTY),
)

internal fun context(
    driving: DrivingState = PARKED,
    capability: CapabilitySnapshot = FULL_CONTROL,
    values: Map<SignalId, SignalValue> = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to SignalValue.Bool(true)),
    userOverrides: Set<SignalId> = emptySet(),
    navigating: Boolean = false,
    oemModeActive: Boolean = false,
    gleoRecentlyUsed: Boolean = false,
    tripActive: Boolean = true,
    tripEnding: Boolean = false,
) = ExecutionContext(
    driving = driving,
    capability = capability,
    values = values,
    userOverrides = userOverrides,
    navigating = navigating,
    oemModeActive = oemModeActive,
    gleoRecentlyUsed = gleoRecentlyUsed,
    tripActive = tripActive,
    tripEnding = tripEnding,
)

/** Like a real car's "max defrost": writing the defroster also switches recirculation (H28). */
internal val coupledCatalog: SignalCatalog = SignalCatalog(
    testCatalog.all.map { spec ->
        if (spec.id == SignalIds.DEFROSTER_FRONT) spec.copy(profile = spec.profile!!.copy(coupledSignals = setOf(SignalIds.RECIRCULATION))) else spec
    },
)
