package kr.routinegongbang.app.ui

import android.content.Context
import kr.routinegongbang.app.R
import kr.routinegongbang.app.ui.model.ActionSummary
import kr.routinegongbang.app.ui.model.ConditionSummary
import kr.routinegongbang.app.ui.model.Outcome
import kr.routinegongbang.app.ui.model.RunSummary
import kr.routinegongbang.app.ui.model.Skip
import kr.routinegongbang.app.ui.model.Status
import kr.routinegongbang.app.ui.model.StatusView
import kr.routinegongbang.app.ui.model.RunLogEntry
import kr.routinegongbang.app.ui.model.TripReceipt
import kr.routinegongbang.app.ui.model.TriggerSummary
import kr.routinegongbang.core.engine.Reason
import kr.routinegongbang.core.model.CompareOp
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue

/** All user-facing wording of the main screen. Exhaustive `when`s make a new reason or status fail to compile until translated. */
internal class Texts(private val context: Context) {

    private fun s(id: Int, vararg args: Any): String = context.getString(id, *args)

    fun signal(id: SignalId): String = SignalNames.names[id]?.let { s(it) } ?: id.value

    /** [state]: a value in a trigger or condition ("켜짐"), not an action ("켜기"). */
    fun value(signal: SignalId, value: SignalValue, state: Boolean = false): String = when {
        signal == SignalIds.WIPER_FRONT_STATE && value == SignalValue.Text("ON") -> s(R.string.value_wiper_on)
        signal == SignalIds.WIPER_FRONT_STATE && value == SignalValue.Text("OFF") -> s(R.string.value_wiper_off)
        signal == SignalIds.DRIVING_MODE && value in SignalNames.drivingModes -> s(SignalNames.drivingModes.getValue(value))
        value is SignalValue.Bool -> s(if (state) Wording.state(value) else if (value.value) R.string.value_on else R.string.value_off)
        value is SignalValue.Num -> SignalNames.number(value.value) + SignalNames.units[signal].orEmpty()
        value is SignalValue.Text -> value.value
        else -> value.toString()
    }

    fun trigger(summary: TriggerSummary): String = when (summary) {
        // "P단으로 주차하면" rather than "기어 선택 PARK"; its short hold is a technicality, not said.
        is TriggerSummary.Edge -> if (summary.signal == SignalIds.GEAR_SELECTION && summary.to == listOf(SignalValue.Text("PARK"))) s(R.string.trigger_parked)
        else held(s(R.string.trigger_edge, signal(summary.signal), summary.to.joinToString("/") { value(summary.signal, it, state = true) }), summary.holdSeconds)
        is TriggerSummary.Threshold -> held(compare(summary.signal, summary.op, summary.value), summary.holdSeconds)
        is TriggerSummary.AllOf -> held(s(R.string.trigger_all_of, summary.compares.joinToString(" · ") { compare(it.signal, it.op, it.value) }), summary.holdSeconds)
        is TriggerSummary.RideElapsed -> s(R.string.trigger_ride_elapsed, summary.minutes)
        is TriggerSummary.At -> s(R.string.trigger_at, summary.hour, summary.minute)
        TriggerSummary.RideStart -> s(R.string.trigger_ride_start)
        TriggerSummary.RideEnd -> s(R.string.trigger_ride_end)
        TriggerSummary.Arrived -> s(R.string.trigger_arrived)
    }

    private fun compare(signal: SignalId, op: CompareOp, value: SignalValue) = s(Wording.compare(op), signal(signal), value(signal, value, state = true))

    /** "… · 30초 동안 유지되면" when the trigger waits for its state to last. */
    private fun held(text: String, seconds: Int) = if (seconds <= 0) text else s(R.string.trigger_held, text, duration(seconds))

    fun duration(seconds: Int): String = when {
        seconds < 60 -> s(R.string.duration_seconds, seconds)
        seconds % 60 == 0 -> s(R.string.duration_minutes, seconds / 60)
        else -> s(R.string.duration_minutes_seconds, seconds / 60, seconds % 60)
    }

    fun condition(summary: ConditionSummary): String = when (summary) {
        ConditionSummary.Weekdays -> s(R.string.condition_weekdays)
        ConditionSummary.Weekends -> s(R.string.condition_weekends)
        is ConditionSummary.Window -> s(R.string.condition_window, summary.from, summary.until)
        is ConditionSummary.Compare -> compare(summary.signal, summary.op, summary.value)
        ConditionSummary.Other -> s(R.string.condition_other)
    }

    /** "비 오는 날 · 차에 요청했어요: 앞유리 성에 제거 켜기 · 내기 순환 켜기" */
    fun runSummary(summary: RunSummary): String = listOfNotNull(
        summary.done.takeIf { it.isNotEmpty() }?.let { s(R.string.run_summary, summary.routineName, it.joinToString(" · ") { a -> action(a) }) },
        summary.told.takeIf { it.isNotEmpty() }?.let { told ->
            // With nothing done, the name opens this line instead.
            val list = told.joinToString(" · ") { action(it) }
            if (summary.done.isEmpty()) s(R.string.run_summary_told_named, summary.routineName, list) else s(R.string.run_summary_told, list)
        },
    ).joinToString("\n")

    fun action(summary: ActionSummary): String = when (summary) {
        is ActionSummary.Set -> "${signal(summary.signal)} ${value(summary.signal, summary.value)}"
        is ActionSummary.Navigate -> s(R.string.action_navigate, place(summary.placeId))
        is ActionSummary.Message -> s(R.string.action_message, message(summary.textRes))
        is ActionSummary.Reminder -> s(R.string.action_reminder, message(summary.textRes))
        is ActionSummary.Wait -> s(R.string.action_wait, duration(summary.seconds))
    }

    private fun place(id: String): String = when (id) {
        "home" -> s(R.string.place_home)
        "office" -> s(R.string.place_office)
        else -> id
    }

    private fun message(key: String): String {
        @Suppress("DiscouragedApi") // Notification keys come from routine JSON, so they are resolved by name.
        val res = context.resources.getIdentifier(key.replace('.', '_'), "string", context.packageName)
        return if (res != 0) context.getString(res) else key
    }

    /** The first word of a result: 했어요 / 알려드렸어요 / 안 했어요, or not finished yet. */
    fun outcome(outcome: Outcome): String = when (outcome) {
        Outcome.DONE -> s(R.string.outcome_done)
        Outcome.TOLD -> s(R.string.outcome_told)
        Outcome.NOT_DONE -> s(R.string.outcome_not_done)
        Outcome.IN_PROGRESS -> s(R.string.outcome_in_progress)
        Outcome.UNCONFIRMED -> s(R.string.outcome_unconfirmed)
    }

    /** In a dry run a wait is skipped, not waited: "했어요" alone would not be true. */
    fun detail(view: StatusView, dryRun: Boolean): String? =
        if (dryRun && view.status == Status.WAITED) s(R.string.result_waited_dry) else detail(view)

    /** "했어요 2 · 알려드렸어요 1" for the recent log. */
    fun outcomeCounts(outcomes: List<Outcome>): String =
        outcomes.groupingBy { it }.eachCount().entries.joinToString(" · ") { (outcome, count) -> "${outcome(outcome)} $count" }

    /** A line of the recent log under the routine's name: why it was skipped, or how its run went and the first reason. */
    fun logLine(entry: RunLogEntry, cooldownMinutes: Int): String = entry.skip?.let { s(R.string.log_skipped) + " · " + skip(it, cooldownMinutes) }
        ?: listOfNotNull(
            // A test run (지금 실행해보기, or asked by voice) is not one the car's situation started.
            s(R.string.badge_dry_run).takeIf { entry.dryRun },
            outcomeCounts(entry.outcomes),
            // The first reason, if any: the demo's "told: control not open" then "done" needs the why.
            entry.results.firstNotNullOfOrNull { StatusView.of(it).reason }?.let { reason(it) },
        ).joinToString(" · ")

    /** One routine of a trip receipt: how its runs went, then how often it was skipped and the last reason why. */
    fun receiptLine(line: TripReceipt.Line, cooldownMinutes: Int): String = listOfNotNull(
        line.outcomes.takeIf { it.isNotEmpty() }?.let { outcomeCounts(it) },
        line.reason?.let { reason(it) },
        line.skips.lastOrNull()?.let { s(R.string.receipt_skipped, line.skips.size, skip(it, cooldownMinutes)) },
    ).joinToString(" · ")

    /** The receipt for the notification: "이름 · 했어요 2", one per routine; [cooldownOf] is each routine's cooldown. */
    fun receipt(receipt: TripReceipt, cooldownOf: (String) -> Int): List<String> =
        receipt.lines.map { "${it.routineName} · ${receiptLine(it, cooldownOf(it.routineId))}" }

    /** The one line under it: the reason when there is one, otherwise what this result means, if it needs saying. */
    fun detail(view: StatusView): String? = if (view.navigation) s(R.string.reason_navi_failed) else view.reason?.let { reason(it) } ?: when (view.status) {
        Status.ALREADY_SET -> s(R.string.result_already_set)
        Status.LATER -> s(R.string.result_later)
        Status.UNCONFIRMED -> s(R.string.result_unconfirmed)
        Status.WAITING -> s(R.string.result_waiting)
        Status.SCHEDULED -> s(R.string.result_scheduled)
        Status.REQUESTED -> s(R.string.result_requested)
        Status.CONFIRMED, Status.FAILED, Status.NOTIFIED, Status.NOTIFIED_INSTEAD, Status.SKIPPED,
        Status.CANCELLED, Status.WAITED -> null
    }

    /** Why a routine did not run when its trigger came; [cooldownMinutes] is the routine's own cooldown. */
    fun skip(kind: Skip.Kind, cooldownMinutes: Int): String = when (kind) {
        Skip.Kind.CONDITION -> s(R.string.skip_condition)
        Skip.Kind.NOT_READ_YET -> s(R.string.skip_not_read_yet)
        Skip.Kind.COOLDOWN -> s(R.string.skip_cooldown, cooldownMinutes)
        Skip.Kind.DAILY_LIMIT -> s(R.string.skip_daily)
        Skip.Kind.TRIP_LIMIT -> s(R.string.skip_trip)
        Skip.Kind.SUSPENDED -> s(R.string.skip_suspended)
        Skip.Kind.STILL_WAITING -> s(R.string.skip_still_waiting)
    }

    fun reason(reason: Reason): String = when (reason) {
        Reason.SDK_NOT_OPEN -> s(R.string.reason_sdk_not_open)
        Reason.NO_PERMISSION -> s(R.string.reason_no_permission)
        Reason.UNSUPPORTED -> s(R.string.reason_unsupported)
        Reason.BODY_WHILE_DRIVING -> s(R.string.reason_body_while_driving)
        Reason.USER_OVERRIDE -> s(R.string.reason_user_override)
        Reason.RATE_LIMITED -> s(R.string.reason_rate_limited)
        Reason.OEM_MODE_ACTIVE -> s(R.string.reason_oem_mode_active)
        Reason.GLEO_RECENT -> s(R.string.reason_gleo_recent)
        Reason.STT_UNAVAILABLE -> s(R.string.reason_stt_unavailable)
        Reason.NAVI_UNAVAILABLE -> s(R.string.reason_navi_unavailable)
        Reason.CONFIRM_DECLINED -> s(R.string.reason_confirm_declined)
        Reason.CONFIRM_TIMEOUT -> s(R.string.reason_confirm_timeout)
        Reason.CONFIRM_FAILED -> s(R.string.reason_confirm_failed)
        Reason.ACTION_FAILED -> s(R.string.reason_action_failed)
        Reason.NOT_PARKED -> s(R.string.reason_not_parked)
        Reason.VISIBILITY -> s(R.string.reason_visibility)
        Reason.CABIN_UNATTENDED -> s(R.string.reason_cabin_unattended)
        Reason.UNSAFE_DIRECTION -> s(R.string.reason_unsafe_direction)
        Reason.SEAT_NOT_AS_REQUIRED -> s(R.string.reason_seat_not_as_required)
        Reason.NO_ONE_TO_CONFIRM -> s(R.string.reason_no_one_to_confirm)
        Reason.CONFLICT -> s(R.string.reason_conflict)
        Reason.FLIP_FLOP -> s(R.string.reason_flip_flop)
        Reason.STATE_CHANGED -> s(R.string.reason_state_changed)
    }

    private val clockFormat = java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm")

    /** "10/29 08:05" in the device zone, for the recent log. */
    fun clock(epochMs: Long): String = clockFormat.format(java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()))

    fun ago(epochMs: Long, nowMs: Long): String {
        val minutes = ((nowMs - epochMs) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> s(R.string.time_just_now)
            minutes < 60 -> s(R.string.time_minutes, minutes.toInt())
            minutes < 24 * 60 -> s(R.string.time_hours, (minutes / 60).toInt())
            else -> s(R.string.time_days, (minutes / (24 * 60)).toInt())
        }
    }
}
