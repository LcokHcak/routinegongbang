package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.Occupancy
import kr.routinegongbang.core.model.Policy
import kr.routinegongbang.core.model.SafetyProfile
import kr.routinegongbang.core.model.SdkModule
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalSpec
import kr.routinegongbang.core.model.SignalValue

/**
 * Runtime safety policy plus tier resolution for a single action.
 * Pure: the executor calls it again right before each write, so a changed context is always respected.
 * Every safety skip is decided before the capability check, so a blocked action never nags the driver.
 */
class ActionPlanner(val catalog: SignalCatalog) {

    /**
     * [limiter] null means "no overall rate limit", used by previews. [history] (the routines' own writes) feeds the
     * flip-back check and is null for previews and dry runs; [rateHistories] together feed the per-signal limits.
     */
    fun plan(
        action: Action,
        policy: Policy,
        ctx: ExecutionContext,
        nowMs: Long,
        limiter: WriteRateLimiter? = null,
        history: WriteHistory? = null,
        rateHistories: List<WriteHistory> = listOfNotNull(history),
    ): ExecutionTier =
        when (action) {
            is Action.Write -> planWrite(action, policy, ctx, nowMs, limiter, history, rateHistories)
            is Action.SetDestination -> planDestination(policy, ctx)
            // The engine splits runs at waits, so a wait never reaches a real run.
            is Action.Notify, is Action.Remind, is Action.Wait -> ExecutionTier.Auto
        }

    private fun planWrite(
        action: Action.Write,
        policy: Policy,
        ctx: ExecutionContext,
        nowMs: Long,
        limiter: WriteRateLimiter?,
        history: WriteHistory?,
        rateHistories: List<WriteHistory>,
    ): ExecutionTier {
        val spec = catalog[action.signal]?.takeIf { it.writable } ?: return ExecutionTier.Skip(Reason.UNSUPPORTED)
        val profile = spec.profile ?: return ExecutionTier.Skip(Reason.UNSUPPORTED)
        val parked = ctx.driving.isParked
        skipReason(spec, profile, action.value, ctx, parked)?.let { return ExecutionTier.Skip(it) }
        if (history?.flipsBack(spec.id, action.value, nowMs, spec.rounding) == true) return ExecutionTier.Skip(Reason.FLIP_FLOP)
        ctx.capability.writeBlocker(spec)?.let { return ExecutionTier.Notify(it) }
        if (limiter != null && !limiter.allows(nowMs)) return ExecutionTier.Skip(Reason.RATE_LIMITED)
        if (overSignalLimit(spec.id, profile, rateHistories, nowMs)) return ExecutionTier.Skip(Reason.RATE_LIMITED)
        val outsideAuto = profile.autoRange?.let { range -> (action.value as? SignalValue.Num)?.value?.let { it !in range } } ?: false
        val needsConfirm = spec.safetyClass.parkedOnly || profile.confirmRequired || outsideAuto ||
            (policy.confirmWhileDriving && !parked) || policy.askBeforeRun
        if (!needsConfirm) return ExecutionTier.Auto
        // A parked confirmation is answered on the screen, by someone in the driver seat.
        if (parked && ctx.seat(SignalIds.SEAT_OCCUPIED_DRIVER) != true) return ExecutionTier.Skip(Reason.NO_ONE_TO_CONFIRM)
        return confirmTier(ctx, parked)
    }

    /**
     * Parked-only, visibility, life support, direction, seat, user override, OEM mode, recent Gleo use — in that
     * order, the most fundamental reason first.
     */
    private fun skipReason(spec: SignalSpec, profile: SafetyProfile, value: SignalValue, ctx: ExecutionContext, parked: Boolean): Reason? {
        val current = ctx.values[spec.id]
        return when {
            spec.safetyClass.parkedOnly && !parked -> Reason.BODY_WHILE_DRIVING
            profile.visibility?.allows(value, current) == false -> Reason.VISIBILITY
            profile.lifeSupport?.weakens(value, current) == true && (!ctx.tripActive || ctx.tripEnding) -> Reason.CABIN_UNATTENDED
            !profile.direction.allows(value, current) -> Reason.UNSAFE_DIRECTION
            !seatAsRequired(profile.occupancy, ctx) -> Reason.SEAT_NOT_AS_REQUIRED
            spec.id in ctx.userOverrides -> Reason.USER_OVERRIDE
            ctx.oemModeActive -> Reason.OEM_MODE_ACTIVE
            ctx.gleoRecentlyUsed -> Reason.GLEO_RECENT
            else -> null
        }
    }

    /** Rate limit per signal: the profile's minute and hour limits. */
    private fun overSignalLimit(signal: SignalId, profile: SafetyProfile, histories: List<WriteHistory>, nowMs: Long): Boolean =
        histories.sumOf { it.countSince(signal, nowMs - MINUTE_MS + 1) } >= profile.maxWritesPerMinute ||
            histories.sumOf { it.countSince(signal, nowMs - HOUR_MS + 1) } >= profile.maxWritesPerHour

    private fun seatAsRequired(occupancy: Occupancy, ctx: ExecutionContext): Boolean = when (occupancy) {
        Occupancy.Any -> true
        is Occupancy.Occupied -> ctx.seat(occupancy.seat) == true
        is Occupancy.Empty -> ctx.seat(occupancy.seat) == false
    }

    /** Never switch an active route without consent. */
    private fun planDestination(policy: Policy, ctx: ExecutionContext): ExecutionTier {
        if (!ctx.capability.isOpen(SdkModule.NAVI)) return ExecutionTier.Notify(Reason.NAVI_UNAVAILABLE)
        val parked = ctx.driving.isParked
        val needsConfirm = ctx.navigating || (policy.confirmWhileDriving && !parked) || policy.askBeforeRun
        return if (needsConfirm) confirmTier(ctx, parked) else ExecutionTier.Auto
    }

    /** While driving only a voice answer is allowed; without speech recognition we can only notify. */
    private fun confirmTier(ctx: ExecutionContext, parked: Boolean): ExecutionTier = when {
        parked -> ExecutionTier.Confirm(voiceOnly = false)
        ctx.capability.isOpen(SdkModule.STT) -> ExecutionTier.Confirm(voiceOnly = true)
        else -> ExecutionTier.Notify(Reason.STT_UNAVAILABLE)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
    }
}
