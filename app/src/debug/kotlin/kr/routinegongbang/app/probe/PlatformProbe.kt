package kr.routinegongbang.app.probe

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Emulator check: Android standard TTS availability and NaviHelper destination setting. */
internal class PlatformProbe(private val context: Context, private val sink: ProbeSink) {

    private var tts: TextToSpeech? = null
    private var navi: NaviHelper? = null

    fun speak() {
        tts?.shutdown()
        tts = TextToSpeech(context) { status ->
            val engine = tts
            sink.log("TTS init status=$status engines=${engine?.engines?.map { it.name }}")
            if (status == TextToSpeech.SUCCESS && engine != null) {
                val lang = engine.setLanguage(Locale.KOREAN)
                sink.log("TTS setLanguage(ko)=$lang")
                val result = engine.speak("루틴공방 음성 테스트입니다.", TextToSpeech.QUEUE_FLUSH, null, "rw-probe")
                sink.log("TTS speak result=$result")
            }
        }
    }

    fun setDestination() {
        val helper = navi ?: NaviHelper(context).also { navi = it }
        helper.initialize(
            onConnected = {
                sink.log("NAVI connected")
                helper.addListener(listener)
                helper.getRouteStateInfo()
                helper.requestRoute(SEOUL_STATION)
                sink.log("NAVI requestRoute(${SEOUL_STATION.poiName}) sent")
            },
            onFailed = { sink.log("NAVI connect FAILED ${it.javaClass.simpleName}: ${it.message}") },
        )
    }

    fun release() {
        tts?.shutdown()
        navi?.release()
    }

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = sink.log("NAVI onRouteStarted $routeStartInfo")
        override fun onRoutingCompleted() = sink.log("NAVI onRoutingCompleted")
        override fun onDestinationChanged() = sink.log("NAVI onDestinationChanged")
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = sink.log("NAVI routeState isRouting=${routeStateInfo.isRouting} mode=${routeStateInfo.naviMode}")
        override fun onError(errorCode: NaviErrorCode) = sink.log("NAVI onError $errorCode")
    }

    private companion object {
        // Public landmark used only as a test destination.
        val SEOUL_STATION = RouteInfo(
            latitude = 37.5547,
            longitude = 126.9707,
            poiName = "서울역",
            poiId = "",
            address = "서울특별시 중구 한강대로 405",
            poiSubId = "0",
        )
    }
}
