package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.Occupancy
import kr.routinegongbang.core.model.Policy
import kr.routinegongbang.core.model.SafetyClass
import kr.routinegongbang.core.model.SdkModule
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalSpec
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.ValueType
import kr.routinegongbang.core.model.WriteDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class ActionPlannerTest {

    private val planner = ActionPlanner(testCatalog)
    private val heatOn = Action.Write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1))
    private val lockDoors = Action.Write(SignalIds.DOOR_LOCK, SignalValue.Bool(true))
    private val closeWindow = Action.Write(SignalIds.WINDOW_POSITION_DRIVER, SignalValue.of(0))
    private val defrostOn = Action.Write(SignalIds.DEFROSTER_FRONT, SignalValue.Bool(true))
    private val goHome = Action.SetDestination("home")
    private val noLimit = WriteRateLimiter(maxPerWindow = 10, windowMs = 60_000)
    private val seated = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to SignalValue.Bool(true))

    private fun plan(action: Action, ctx: ExecutionContext, policy: Policy = Policy(), limiter: WriteRateLimiter = noLimit) =
        planner.plan(action, policy, ctx, nowMs = 0, limiter = limiter)

    @Test
    fun `comfort write while parked runs automatically`() {
        assertEquals(ExecutionTier.Auto, plan(heatOn, context(PARKED)))
    }

    @Test
    fun `comfort write while driving runs automatically unless confirmation is requested`() {
        assertEquals(ExecutionTier.Auto, plan(heatOn, context(DRIVING)))
    }

    @Test
    fun `confirmation while driving is voice only when speech recognition is open`() {
        assertEquals(ExecutionTier.Confirm(voiceOnly = true), plan(heatOn, context(DRIVING), Policy(confirmWhileDriving = true)))
    }

    @Test
    fun `confirmation while driving without speech recognition downgrades to notify`() {
        val noStt = FULL_CONTROL.copy(openSdks = FULL_CONTROL.openSdks - SdkModule.STT)
        assertEquals(ExecutionTier.Notify(Reason.STT_UNAVAILABLE), plan(heatOn, context(DRIVING, noStt), Policy(confirmWhileDriving = true)))
    }

    @Test
    fun `write without control sdk downgrades to notify with the reason`() {
        assertEquals(ExecutionTier.Notify(Reason.SDK_NOT_OPEN), plan(heatOn, context(PARKED, CONTROL_CLOSED)))
    }

    @Test
    fun `H05 body action while driving is skipped`() {
        assertEquals(ExecutionTier.Skip(Reason.BODY_WHILE_DRIVING), plan(lockDoors, context(DRIVING)))
    }

    @Test
    fun `body action while driving is skipped even when control is closed`() {
        assertEquals(ExecutionTier.Skip(Reason.BODY_WHILE_DRIVING), plan(lockDoors, context(DRIVING, CONTROL_CLOSED)))
    }

    @Test
    fun `body action while parked needs a confirmation by button`() {
        assertEquals(ExecutionTier.Confirm(voiceOnly = false), plan(lockDoors, context(PARKED)))
    }

    @Test
    fun `H21 signal changed by the user this trip is not overwritten`() {
        val ctx = context(PARKED, userOverrides = setOf(SignalIds.STEERING_WHEEL_HEAT))
        assertEquals(ExecutionTier.Skip(Reason.USER_OVERRIDE), plan(heatOn, ctx))
    }

    @Test
    fun `defer writes while an OEM mode or a recent Gleo command is active`() {
        assertEquals(ExecutionTier.Skip(Reason.OEM_MODE_ACTIVE), plan(heatOn, context(PARKED, oemModeActive = true)))
        assertEquals(ExecutionTier.Skip(Reason.GLEO_RECENT), plan(heatOn, context(PARKED, gleoRecentlyUsed = true)))
    }

    @Test
    fun `H06 closing the window while parked needs the driver to confirm`() {
        val open = seated + (SignalIds.WINDOW_POSITION_DRIVER to SignalValue.of(30))
        assertEquals(ExecutionTier.Confirm(voiceOnly = false), plan(closeWindow, context(PARKED, values = open)))
    }

    @Test
    fun `H07 a window is never opened, and an unknown position cannot prove it closes`() {
        val half = seated + (SignalIds.WINDOW_POSITION_DRIVER to SignalValue.of(30))
        val wider = Action.Write(SignalIds.WINDOW_POSITION_DRIVER, SignalValue.of(60))
        assertEquals(ExecutionTier.Skip(Reason.UNSAFE_DIRECTION), plan(wider, context(PARKED, values = half)))
        assertEquals(ExecutionTier.Skip(Reason.UNSAFE_DIRECTION), plan(closeWindow, context(PARKED, values = seated)))
    }

    @Test
    fun `H09 turning the defroster off is never automated, parked or driving`() {
        val off = Action.Write(SignalIds.DEFROSTER_FRONT, SignalValue.Bool(false))
        assertEquals(ExecutionTier.Skip(Reason.VISIBILITY), plan(off, context(DRIVING)))
        assertEquals(ExecutionTier.Skip(Reason.VISIBILITY), plan(off, context(PARKED)))
        assertEquals(ExecutionTier.Auto, plan(Action.Write(SignalIds.DEFROSTER_FRONT, SignalValue.Bool(true)), context(DRIVING)))
    }

    @Test
    fun `H29 cabin climate is never weakened without a trip or once the driver has left`() {
        val hot = Action.Write(SignalIds.HVAC_TEMP_DRIVER, SignalValue.of(27))
        assertEquals(ExecutionTier.Skip(Reason.CABIN_UNATTENDED), plan(hot, context(PARKED, tripActive = false)))
        assertEquals(ExecutionTier.Skip(Reason.CABIN_UNATTENDED), plan(hot, context(PARKED, tripEnding = true)))
        assertEquals(ExecutionTier.Auto, plan(hot, context(PARKED)), "with a driver it is just a setting")
    }

    @Test
    fun `preheating to a comfortable temperature before anyone gets in is fine`() {
        // The rule forbids weakening the climate, not a 07:30 warm-up.
        assertEquals(ExecutionTier.Auto, plan(Action.Write(SignalIds.HVAC_TEMP_DRIVER, SignalValue.of(22)), context(PARKED, tripActive = false)))
    }

    @Test
    fun `H07 a body action that needs a confirmation is skipped when nobody is in the driver seat`() {
        val empty = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to SignalValue.Bool(false))
        assertEquals(ExecutionTier.Skip(Reason.NO_ONE_TO_CONFIRM), plan(lockDoors, context(PARKED, values = empty)))
        assertEquals(ExecutionTier.Skip(Reason.NO_ONE_TO_CONFIRM), plan(lockDoors, context(PARKED, values = emptyMap())), "unknown seat")
    }

    @Test
    fun `H15 heat above the automatic range asks first`() {
        val hottest = Action.Write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(3))
        assertEquals(ExecutionTier.Confirm(voiceOnly = false), plan(hottest, context(PARKED)))
        assertEquals(ExecutionTier.Confirm(voiceOnly = true), plan(hottest, context(DRIVING)))
        assertEquals(ExecutionTier.Auto, plan(Action.Write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(2)), context(DRIVING)))
    }

    @Test
    fun `rate limit stops further writes`() {
        val full = WriteRateLimiter(maxPerWindow = 1, windowMs = 60_000).record(nowMs = 0)
        assertEquals(ExecutionTier.Skip(Reason.RATE_LIMITED), plan(heatOn, context(PARKED), limiter = full))
    }

    @Test
    fun `destination while not navigating runs automatically`() {
        assertEquals(ExecutionTier.Auto, plan(goHome, context(DRIVING, CONTROL_CLOSED)))
    }

    @Test
    fun `H26 destination while already navigating needs confirmation`() {
        assertEquals(ExecutionTier.Confirm(voiceOnly = false), plan(goHome, context(PARKED, navigating = true)))
        assertEquals(ExecutionTier.Confirm(voiceOnly = true), plan(goHome, context(DRIVING, navigating = true)))
    }

    @Test
    fun `destination while navigating and driving without speech recognition only notifies`() {
        assertEquals(ExecutionTier.Notify(Reason.STT_UNAVAILABLE), plan(goHome, context(DRIVING, CONTROL_CLOSED, navigating = true)))
    }

    @Test
    fun `destination without navigation sdk downgrades to notify`() {
        val noNavi = FULL_CONTROL.copy(openSdks = FULL_CONTROL.openSdks - SdkModule.NAVI)
        assertEquals(ExecutionTier.Notify(Reason.NAVI_UNAVAILABLE), plan(goHome, context(PARKED, noNavi)))
    }

    @Test
    fun `notify and remind actions always run`() {
        assertEquals(ExecutionTier.Auto, plan(Action.Notify("n"), context(DRIVING, CONTROL_CLOSED)))
        assertEquals(ExecutionTier.Auto, plan(Action.Remind("r"), context(DRIVING, CONTROL_CLOSED)))
    }

    @Test
    fun `unknown signal is skipped instead of crashing`() {
        val unknown = Action.Write(SignalId("x.unknown"), SignalValue.of(1))
        assertEquals(ExecutionTier.Skip(Reason.UNSUPPORTED), plan(unknown, context(PARKED)))
    }

    @Test
    fun `H14 a seat-bound write runs only when the seat is known to be occupied`() {
        val warm = Action.Write(SignalIds.SEAT_HEAT_PASSENGER, SignalValue.of(1))
        fun plan(passenger: SignalValue?) = planner.plan(
            warm, Policy(), context(PARKED, values = seated + listOfNotNull(passenger?.let { SignalIds.SEAT_OCCUPIED_PASSENGER to it })),
            nowMs = 0, limiter = noLimit,
        )

        assertEquals(ExecutionTier.Auto, plan(SignalValue.Bool(true)))
        assertEquals(ExecutionTier.Skip(Reason.SEAT_NOT_AS_REQUIRED), plan(SignalValue.Bool(false)))
        assertEquals(ExecutionTier.Skip(Reason.SEAT_NOT_AS_REQUIRED), plan(null), "an unknown seat proves nothing")
    }

    @Test
    fun `H18 energy settings are parked-only like body parts`() {
        val chargeLimit = SignalSpec(
            SignalId("ev.charge.limit"), ValueType.NUMBER, SafetyClass.ENERGY, SdkModule.VEHICLE_STATE, SdkModule.VEHICLE_CONTROL,
            range = 50.0..100.0, profile = testCatalog.getOrThrow(SignalIds.DOOR_LOCK).profile!!.copy(direction = WriteDirection.Any),
        )
        val catalog = SignalCatalog(testCatalog.all + chargeLimit)
        val capability = FULL_CONTROL.copy(access = FULL_CONTROL.access + (chargeLimit.id to Access.READ_WRITE))
        val set80 = Action.Write(chargeLimit.id, SignalValue.of(80))
        fun plan(driving: DrivingState) = ActionPlanner(catalog).plan(set80, Policy(), context(driving, capability), nowMs = 0, limiter = noLimit)

        assertEquals(ExecutionTier.Skip(Reason.BODY_WHILE_DRIVING), plan(DRIVING))
        assertEquals(ExecutionTier.Confirm(voiceOnly = false), plan(PARKED))
    }

    @Test
    fun `H02 the app does not flip a signal back within two minutes`() {
        val history = WriteHistory()
            .record(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1), atMs = 0)
            .record(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(2), atMs = 30_000)
        fun planAt(nowMs: Long) = planner.plan(heatOn, Policy(), context(PARKED), nowMs, noLimit, history)
        assertEquals(ExecutionTier.Skip(Reason.FLIP_FLOP), planAt(60_000))
        assertEquals(ExecutionTier.Auto, planAt(3 * 60_000), "after the window it is a new decision")
        val settle = Action.Write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(0))
        assertEquals(ExecutionTier.Auto, planner.plan(settle, Policy(), context(PARKED), 60_000, noLimit, history), "one-way adjustments are fine")
    }

    @Test
    fun `H20 each signal keeps to its own minute and hour limits`() {
        val perMinute = testCatalog.getOrThrow(SignalIds.STEERING_WHEEL_HEAT).profile!!.maxWritesPerMinute
        val busy = (0 until perMinute).fold(WriteHistory()) { h, i -> h.record(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(i % 2 + 1), atMs = i * 1_000L) }
        assertEquals(ExecutionTier.Skip(Reason.RATE_LIMITED), planner.plan(Action.Write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(0)), Policy(), context(PARKED), 10_000, noLimit, busy))
        assertEquals(ExecutionTier.Auto, planner.plan(defrostOn, Policy(), context(PARKED), 10_000, noLimit, busy), "other signals are not affected")

        val perHour = testCatalog.getOrThrow(SignalIds.STEERING_WHEEL_HEAT).profile!!.maxWritesPerHour
        val day = (0 until perHour).fold(WriteHistory()) { h, i -> h.record(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(i % 3), atMs = i * 120_000L) }
        val later = perHour * 120_000L
        assertEquals(ExecutionTier.Skip(Reason.RATE_LIMITED), planner.plan(heatOn, Policy(), context(PARKED), later - 1, noLimit, day))
    }

    @Test
    fun `rate limit is ignored when no limiter is given`() {
        assertEquals(ExecutionTier.Auto, planner.plan(heatOn, Policy(), context(PARKED), nowMs = 0, limiter = null))
    }
}
