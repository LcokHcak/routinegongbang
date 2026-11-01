package kr.routinegongbang.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kr.routinegongbang.app.platform.CrashRecorder
import kr.routinegongbang.app.platform.PermissionGate
import kr.routinegongbang.app.service.DebugSettings
import kr.routinegongbang.app.service.EngineLog
import kr.routinegongbang.app.service.RoutineService
import kr.routinegongbang.app.ui.AppState
import kr.routinegongbang.app.ui.QuestionOverlay
import kr.routinegongbang.app.ui.RoutineScreen
import kr.routinegongbang.app.ui.RoutineTheme

/**
 * Main screen: asks for the runtime permissions, starts the engine service and shows routines, results and
 * the habit suggestion from [AppState]. The activity is not distraction-optimised, so AAOS hides it while driving.
 */
class MainActivity : ComponentActivity() {

    private var contentShown = false
    /** The permission dialog is up; its answer comes to this activity, or to the one recreated from it. */
    private var asking = false
    /** One reference, so onDestroy clears only what this activity set. */
    private val fix: () -> Unit = ::fixPermissions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashRecorder.install(this)
        AppState.fixPermissions = fix
        if (savedInstanceState != null) {
            // Ask once per launch, not on every recreation (day/night switches recreate the activity on AAOS). Recreated
            // while the dialog is up, it waits for the answer as the first one did.
            asking = savedInstanceState.getBoolean(KEY_ASKING)
            if (!asking) showContentAndStart()
            return
        }
        val missing = missingPermissions()
        // The permission dialogs come before the first frame: drawing text under them can corrupt the emulator's glyph
        // cache for the whole process (seen in 3 of 7 first starts; every text blank until restarted).
        if (missing.isEmpty()) showContentAndStart() else ask(missing)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ASKING, asking)
    }

    override fun onResume() {
        super.onResume()
        // Granted in the settings meanwhile: the banner goes, and the engine starts again with it.
        refreshMissing()
    }

    /** Starts the service either way: missing permissions only lower the capability snapshot, they never crash it. */
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        asking = false
        results.filterValues { granted -> !granted }.keys.forEach { EngineLog.add("permission denied: $it") }
        refreshMissing()
        showContentAndStart()
    }

    private fun ask(missing: List<String>) {
        asking = true
        permissionRequest.launch(missing.toTypedArray())
    }

    /**
     * The capability snapshot and the car subscriptions are built when the engine starts, so a car permission granted
     * later needs a fresh start (otherwise the banner goes, the signals stay unknown). Not in demo mode, which
     * reads no car and whose story a restart would cut.
     */
    private fun refreshMissing() {
        val now = missingPermissions().toSet()
        val granted = AppState.missingPermissions.value - now
        AppState.setMissingPermissions(now)
        if (PermissionGate.blocking(granted).isNotEmpty() && RoutineService.running != null && !DebugSettings.fakeVehicle(this)) {
            EngineLog.add("car permission granted: engine restarted")
            RoutineService.restart(this)
        }
    }

    /** The banner's "권한 다시 요청": the dialog while the system still asks, otherwise the app's settings page. */
    private fun fixPermissions() {
        val missing = missingPermissions()
        if (missing.isEmpty()) return
        if (missing.any { shouldShowRequestPermissionRationale(it) }) return ask(missing)
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (e: ActivityNotFoundException) {
            EngineLog.add("app settings page not available")
        }
    }

    private fun showContentAndStart() {
        if (!contentShown) {
            contentShown = true
            setContent {
                RoutineTheme {
                    val state by AppState.ui.collectAsStateWithLifecycle()
                    // "N분 전" only needs to move while the screen is visible.
                    val lifecycle = LocalLifecycleOwner.current.lifecycle
                    val now by produceState(System.currentTimeMillis(), lifecycle) {
                        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                            while (true) {
                                value = System.currentTimeMillis()
                                delay(CLOCK_REFRESH_MS)
                            }
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        RoutineScreen(state, AppState.commands, AppState.debug, now)
                        QuestionOverlay(parked = state.status.parked)
                    }
                }
            }
        }
        RoutineService.start(this)
    }

    override fun onDestroy() {
        if (AppState.fixPermissions === fix) AppState.fixPermissions = null
        super.onDestroy()
    }

    /**
     * Only what the user can grant: a permission this car's system keeps for itself (not runtime, or not defined) would
     * leave the banner up for good, its button leading nowhere.
     */
    private fun missingPermissions(): List<String> =
        requestedPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED && grantable(it) }

    private fun grantable(permission: String): Boolean =
        try {
            packageManager.getPermissionInfo(permission, 0).protection == PermissionInfo.PROTECTION_DANGEROUS
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    private fun requestedPermissions(): List<String> =
        packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()

    private companion object {
        const val CLOCK_REFRESH_MS = 30_000L
        const val KEY_ASKING = "asking"
    }
}
