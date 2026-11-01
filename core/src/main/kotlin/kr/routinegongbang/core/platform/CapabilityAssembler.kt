package kr.routinegongbang.core.platform

import kr.routinegongbang.core.engine.Access
import kr.routinegongbang.core.engine.CapabilitySnapshot
import kr.routinegongbang.core.engine.EngineEvent
import kr.routinegongbang.core.model.AreaKeys
import kr.routinegongbang.core.model.SdkModule
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalValue

enum class PermissionKind { READ, WRITE }

/**
 * Per signal, the lowest of SDK availability, the access level reported by the
 * capability check, and the runtime permissions actually granted. A failed or missing check means NONE.
 */
class CapabilityAssembler(private val catalog: SignalCatalog) {

    fun assemble(
        openSdks: Set<SdkModule>,
        reported: Map<SignalId, Access?>,
        granted: (SignalId, PermissionKind) -> Boolean,
    ): CapabilitySnapshot {
        val access = catalog.all.associate { spec ->
            val level = reported[spec.id] ?: Access.NONE
            val read = level.canRead && granted(spec.id, PermissionKind.READ)
            val write = level.canWrite && granted(spec.id, PermissionKind.WRITE)
            spec.id to accessOf(read, write)
        }
        return CapabilitySnapshot(openSdks, access)
    }

    private val Access.canRead get() = this == Access.READ || this == Access.READ_WRITE
    private val Access.canWrite get() = this == Access.WRITE || this == Access.READ_WRITE

    private fun accessOf(read: Boolean, write: Boolean): Access = when {
        read && write -> Access.READ_WRITE
        read -> Access.READ
        write -> Access.WRITE
        else -> Access.NONE
    }
}

/**
 * Startup snapshot. Some SDK domains (HVAC) have no `getCurrent…`; their first subscription
 * callback is the current value. Until [release], callbacks are kept as readings (latest wins); afterwards
 * they are forwarded as events. Readings never trigger routines. Thread-safe.
 */
class StartupBuffer(private val catalog: SignalCatalog) {
    private val lock = Any()
    private val readings = LinkedHashMap<SignalId, SignalValue>()
    private var forward: ((EngineEvent.SignalChanged) -> Unit)? = null

    fun accept(event: EngineEvent.SignalChanged) {
        val sink = synchronized(lock) {
            forward ?: run {
                val home = catalog[event.signal]?.area ?: AreaKeys.GLOBAL
                if (event.area == null || event.area == home) readings[event.signal] = event.value
                null
            }
        }
        sink?.invoke(event)
    }

    /** Returns the startup readings and forwards every later value to [sink]. */
    fun release(sink: (EngineEvent.SignalChanged) -> Unit): Map<SignalId, SignalValue> = synchronized(lock) {
        check(forward == null) { "StartupBuffer released twice" }
        forward = sink
        readings.toMap()
    }
}
