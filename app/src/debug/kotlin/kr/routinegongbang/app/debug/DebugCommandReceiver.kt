package kr.routinegongbang.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kr.routinegongbang.app.service.EngineLog
import kr.routinegongbang.app.service.RoutineService
import kr.routinegongbang.core.engine.EngineEvent

/**
 * Debug-only engine commands for emulator checks and demo rehearsals (never in release):
 *   adb shell am broadcast -a kr.routinegongbang.app.DEBUG -p kr.routinegongbang.app --es cmd run --es routine go_home
 *   adb shell am broadcast -a kr.routinegongbang.app.DEBUG -p kr.routinegongbang.app --es cmd tick
 * `run` is the same RunNow path as the "지금 실행해보기" button: only while parked, safety rules still apply.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = RoutineService.running ?: return EngineLog.add("DEBUG engine not running")
        when (val cmd = intent.getStringExtra("cmd")) {
            "run" -> intent.getStringExtra("routine")?.let { engine.post(EngineEvent.RunNow(it)) } ?: EngineLog.add("DEBUG run needs --es routine <id>")
            "tick" -> engine.post(EngineEvent.Tick)
            "arrived" -> engine.post(EngineEvent.NaviArrived)
            else -> EngineLog.add("DEBUG unknown command $cmd")
        }
        EngineLog.add("DEBUG ${intent.getStringExtra("cmd")} ${intent.getStringExtra("routine").orEmpty()}")
    }
}
