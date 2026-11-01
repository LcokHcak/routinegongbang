package kr.routinegongbang.app.platform

/**
 * Which missing runtime permissions matter: without the car ones the app cannot tell parked from
 * driving, so nothing can be edited or run, and the screen must say why; without notifications it only goes quiet.
 */
internal object PermissionGate {
    private const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    /** Parked or driving is read from these two (RoutineService: gear and speed). */
    private val PARKING = setOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN")

    enum class Banner { PARKING_UNKNOWN, SOME_CAR_DATA, NOTIFICATIONS_OFF }

    fun blocking(missing: Set<String>): Set<String> =
        missing.filterTo(mutableSetOf()) { it.startsWith("android.car.permission.") || it.startsWith("pleos.car.permission.") }

    fun notifyingOff(missing: Set<String>): Boolean = NOTIFICATIONS in missing

    /** What the banner says: "정차 여부를 알 수 없어요" only when that is true. */
    fun banner(missing: Set<String>): Banner? = when {
        missing.any { it in PARKING } -> Banner.PARKING_UNKNOWN
        blocking(missing).isNotEmpty() -> Banner.SOME_CAR_DATA
        notifyingOff(missing) -> Banner.NOTIFICATIONS_OFF
        else -> null
    }
}
