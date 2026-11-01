package kr.routinegongbang.app.probe

import android.util.Log

/** All probe output goes to logcat with this tag so results can be read via `adb logcat -s RW_PROBE`. */
internal const val PROBE_TAG = "RW_PROBE"

internal fun interface ProbeSink {
    fun log(message: String)
}

/** Generic description: the published AAR did not expose VehicleException at compile time, so print class + message. */
internal fun describe(e: Exception): String = "${e.javaClass.name}: ${e.message}"

internal fun logcat(message: String) {
    Log.i(PROBE_TAG, message)
}
