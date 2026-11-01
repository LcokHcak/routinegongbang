package kr.routinegongbang.core.habit

import kr.routinegongbang.core.model.SignalCatalog
import java.time.Instant
import java.time.ZoneId

internal val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
internal val habitCatalog = SignalCatalog.default()

/** 2026-10-26 is a Monday. */
internal fun at(date: String, time: String): Instant = Instant.parse("${date}T$time:00+09:00")

internal const val MINUTE = 60_000L
internal const val DAY = 24 * 60 * MINUTE
