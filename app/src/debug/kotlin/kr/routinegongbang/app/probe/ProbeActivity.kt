package kr.routinegongbang.app.probe

import ai.pleos.playground.vehicle.Vehicle
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView

/**
 * Debug-only probe screen. Commands are sent with adb so checks can run without touching the screen:
 *   adb shell am start -n kr.routinegongbang.app/.probe.ProbeActivity --es cmd <command>
 * Commands: wiper, heat_on, heat_off, defrost_on, defrost_off, roof, tts, navi
 */
class ProbeActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var output: TextView
    private lateinit var vehicle: Vehicle
    private lateinit var vehicleProbe: VehicleProbe
    private lateinit var platformProbe: PlatformProbe

    private val sink = ProbeSink { message ->
        logcat(message)
        main.post { output.append("$message\n") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { textSize = 14f }
        setContentView(ScrollView(this).apply { addView(output) })

        vehicle = Vehicle(this).also { it.initialize() }
        vehicleProbe = VehicleProbe(vehicle, sink)
        platformProbe = PlatformProbe(this, sink)
        sink.log("PROBE start (waiting ${SERVICE_CONNECT_DELAY_MS}ms for vehicle service)")

        main.postDelayed({
            vehicleProbe.checkCapabilities()
            vehicleProbe.subscribe()
            handle(intent)
        }, SERVICE_CONNECT_DELAY_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        platformProbe.release()
        vehicle.release()
        super.onDestroy()
    }

    private fun handle(intent: Intent?) {
        val cmd = intent?.getStringExtra("cmd") ?: return
        sink.log("CMD $cmd")
        when (cmd) {
            "wiper" -> vehicleProbe.readWiper()
            "heat_on" -> vehicleProbe.writeSteeringHeat(1)
            "heat_off" -> vehicleProbe.writeSteeringHeat(0)
            "defrost_on" -> vehicleProbe.writeDefroster(true)
            "defrost_off" -> vehicleProbe.writeDefroster(false)
            "roof" -> vehicleProbe.checkRoof()
            "tts" -> platformProbe.speak()
            "navi" -> platformProbe.setDestination()
            else -> sink.log("CMD unknown: $cmd")
        }
    }

    private companion object {
        const val SERVICE_CONNECT_DELAY_MS = 3_000L
    }
}
