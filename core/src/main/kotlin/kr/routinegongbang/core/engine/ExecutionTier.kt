package kr.routinegongbang.core.engine

/** Why an action was downgraded, skipped, cancelled or failed. Shown in the debug panel and the UI. */
enum class Reason {
    SDK_NOT_OPEN,
    NO_PERMISSION,
    UNSUPPORTED,
    BODY_WHILE_DRIVING,
    USER_OVERRIDE,
    RATE_LIMITED,
    OEM_MODE_ACTIVE,
    GLEO_RECENT,
    STT_UNAVAILABLE,
    NAVI_UNAVAILABLE,
    CONFIRM_DECLINED,
    CONFIRM_TIMEOUT,
    /** The confirmation UI or voice prompt crashed; treated like a refusal. */
    CONFIRM_FAILED,
    ACTION_FAILED,
    /** Dry run requested while the car is not parked. */
    NOT_PARKED,
    /** The write would make the view out of the car worse. */
    VISIBILITY,
    /** No trip, or the driver has left; someone left in the car may depend on the climate. */
    CABIN_UNATTENDED,
    /** The value goes against the signal's only safe direction (never unlock, never open a window). */
    UNSAFE_DIRECTION,
    /** The seat is not known to be occupied (or empty) as the write requires. */
    SEAT_NOT_AS_REQUIRED,
    /** A confirmation is needed but nobody is known to sit in the driver seat to give it. */
    NO_ONE_TO_CONFIRM,
    /** A routine earlier in the list writes the same signal for this event. */
    CONFLICT,
    /** The app would flip the signal back within a few minutes (A → B → A). */
    FLIP_FLOP,
    /** A change arrived while waiting for the confirmation, so the rest of the plan was dropped. */
    STATE_CHANGED,
}

/** How one action will be executed. */
sealed interface ExecutionTier {
    data object Auto : ExecutionTier
    /** [voiceOnly] is true while driving: no buttons may be shown. */
    data class Confirm(val voiceOnly: Boolean) : ExecutionTier
    data class Notify(val reason: Reason) : ExecutionTier
    data class Skip(val reason: Reason) : ExecutionTier
}
