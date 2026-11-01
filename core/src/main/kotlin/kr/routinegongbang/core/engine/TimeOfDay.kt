package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.Trigger
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** A tick may arrive this late (Doze, slow loop) and still fire; anything later is a clock jump, not a tick. */
const val TIME_TRIGGER_MAX_LATENESS_MS: Long = 5 * 60_000

/**
 * True when the most recent occurrence of this local time lies in (since, now] and is at most
 * [maxLatenessMs] old. Never catches up after a restart, a clock sync jump or a long suspend, never fires
 * twice for one interval, and never fires when the clock moved backwards.
 */
fun Trigger.TimeOfDay.crossed(since: Instant, now: Instant, zone: ZoneId, maxLatenessMs: Long = TIME_TRIGGER_MAX_LATENESS_MS): Boolean {
    if (!now.isAfter(since)) return false
    val local = now.atZone(zone)
    val today = local.with(LocalTime.of(hour, minute)).toInstant()
    val latest = if (today.isAfter(now)) local.minusDays(1).with(LocalTime.of(hour, minute)).toInstant() else today
    return latest.isAfter(since) && Duration.between(latest, now).toMillis() <= maxLatenessMs
}
