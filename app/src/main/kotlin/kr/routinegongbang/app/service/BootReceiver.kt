package kr.routinegongbang.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kr.routinegongbang.app.BuildConfig

/** Starts the engine service after the head unit boots (BOOT_COMPLETED may start foreground services). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // A release head unit always starts on the real car: demo mode never silently outlives a restart.
        if (!BuildConfig.DEBUG) DebugSettings.setFakeVehicle(context, false)
        try {
            RoutineService.start(context)
        } catch (e: IllegalStateException) {
            EngineLog.add("boot start refused: ${e.message}")
        }
    }
}
