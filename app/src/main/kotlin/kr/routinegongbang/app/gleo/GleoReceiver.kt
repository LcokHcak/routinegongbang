package kr.routinegongbang.app.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kr.routinegongbang.app.service.EngineLog
import kr.routinegongbang.app.ui.AppState
import kr.routinegongbang.app.ui.UiState

/**
 * Gleo calls the functions of assets/schema.json here ("component": "service" is a broadcast): the function name is
 * the intent action, the parameters one JSON string extra. The reply is one APP_ACTION_RESPONSE broadcast, sent well
 * within Gleo's 7 s.
 *
 * Exported, as Gleo must reach it, and the guide names no permission or package to check, so any app on the head unit
 * can call it and hear the reply. It therefore does only what is harmless whoever asks: run a switched-on
 * routine while parked under every safety rule, switch one off any time and on only while parked, and list names with
 * on/off; only the declared functions, rationed. The log keeps the function and status only.
 * Left open by the contract: the reply tells any listener the routine names and, by "only while parked", whether the
 * car is moving; and anyone can forge a reply in this app's name, which Gleo cannot tell apart.
 */
class GleoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Only the declared functions: anything else is dropped before it is read, logged or answered.
        val function = intent.action?.takeIf(Gleo::handles) ?: return
        if (!gate.tryEnter()) {
            runCatching { send(context, Gleo.busy(function)) }
            return
        }
        val pending = goAsync()
        scope.launch {
            try {
                val reply = replyTo(function, intent)
                EngineLog.add("GLEO $function -> ${reply.status.wire}")
                send(context, reply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EngineLog.add("GLEO $function: no reply sent (${e.javaClass.simpleName})")
            } finally {
                gate.leave()
                pending.finish()
            }
        }
    }

    private fun send(context: Context, reply: GleoReply) = context.sendBroadcast(
        Intent(ACTION_RESPONSE)
            .putExtra(EXTRA_PACKAGE, context.packageName)
            .putExtra(EXTRA_RESPONSE, reply.toJson()),
    )

    /** Never throws: a broken request or a failure inside gets a FAIL reply, not a crash of the engine's process. */
    private suspend fun replyTo(function: String, intent: Intent): GleoReply = try {
        handle(function, GleoParams.parse(intent.getStringExtra(EXTRA_PARAMS)))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        EngineLog.add("GLEO $function failed (${e.javaClass.simpleName})")
        Gleo.failed(function)
    }

    private suspend fun handle(function: String, params: GleoParams): GleoReply {
        val commands = AppState.commands ?: return Gleo.notReady(function)
        val state = AppState.ui.value
        val routines = state.routines.map { GleoRoutine(it.routine.id, it.routine.name, it.routine.enabled) }
        return when (function) {
            Gleo.GET_ROUTINES -> Gleo.list(routines)
            Gleo.SET_ROUTINE_ENABLED -> when (val decision = Gleo.setEnabled(routines, params, parked = state.status.parked)) {
                is GleoDecision.SetEnabled -> Gleo.enabledChanged(decision.name, decision.enabled).also { commands.setEnabled(decision.routineId, decision.enabled) }
                is GleoDecision.Reply -> decision.reply
                is GleoDecision.Start -> Gleo.unknown(function)
            }
            Gleo.START_ROUTINE -> when (val decision = Gleo.start(routines, params, parked = state.status.parked)) {
                is GleoDecision.Start -> run(decision, state)
                is GleoDecision.Reply -> decision.reply
                is GleoDecision.SetEnabled -> Gleo.unknown(function)
            }
            else -> Gleo.unknown(function)
        }
    }

    /**
     * Asks for the run, then waits for its report or the reason it did not run, and for the car's confirmations, within
     * Gleo's time; what is known by then, read afresh, is said.
     */
    private suspend fun run(decision: GleoDecision.Start, before: UiState): GleoReply {
        val previous = before.routines.firstOrNull { it.routine.id == decision.routineId }?.lastRun
        val commands = AppState.commands ?: return Gleo.notReady(Gleo.START_ROUTINE)
        commands.runNow(decision.routineId)
        val heard = { ui: UiState -> GleoHeard.of(ui, decision.routineId, previous, before.log) }
        withTimeoutOrNull(RUN_WAIT_MS) {
            AppState.ui.map(heard).first { it is GleoHeard.Skipped || (it is GleoHeard.Ran && it.final) }
        }
        return Gleo.answer(decision.name, heard(AppState.ui.value))
    }

    private companion object {
        const val EXTRA_PARAMS = "ACTION_PARAM"
        const val ACTION_RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
        const val EXTRA_PACKAGE = "packageName"
        const val EXTRA_RESPONSE = "response"
        /** Gleo gives up after 7 s; the rest is for a cold start and the broadcasts both ways. */
        const val RUN_WAIT_MS = 4_000L
        val gate = GleoGate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> EngineLog.add("GLEO crashed (${e.javaClass.simpleName})") })
    }
}
