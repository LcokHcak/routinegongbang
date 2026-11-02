package kr.routinegongbang.app.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kr.routinegongbang.app.R
import kr.routinegongbang.app.ui.Texts
import kr.routinegongbang.core.engine.ConfirmRequest
import kr.routinegongbang.core.engine.Confirmer
import kr.routinegongbang.core.engine.Notifier
import kr.routinegongbang.core.engine.NotifyMessage
import kr.routinegongbang.core.model.Action
import java.util.concurrent.atomic.AtomicInteger

internal object Channels {
    const val ROUTINES = "routines"
    const val SERVICE = "service"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(ROUTINES, context.getString(R.string.channel_routines), NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(SERVICE, context.getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN))
    }
}

/**
 * Notifications; voice falls back to this until the Pleos TTS SDK opens.
 * Text keys resolve to string resources ("notify.downgraded" → `notify_downgraded`); unknown keys are shown as is.
 */
internal class AndroidNotifier(
    private val context: Context,
    private val hold: NoticeHold? = null,
    private val quiet: () -> Boolean = { false },
) : Notifier {
    private val ids = AtomicInteger(NOTIFY_BASE_ID)

    override suspend fun notify(message: NotifyMessage) {
        deliver(message)
    }

    /**
     * While driving a notice waits for parking and the controller releases it as one digest; false
     * then, so the card says "정차하면 알려 드려요" rather than "알려드렸어요". A downgrade is told by
     * the run summary, which waits the same way.
     */
    override suspend fun deliver(message: NotifyMessage): Boolean {
        if (!NotifyRouting.showsOnItsOwn(message)) return hold == null || !quiet()
        val text = text(message)
        val showNow = if (hold == null) text else hold.offer(text, quiet) ?: return false
        post(showNow)
        return true
    }

    /**
     * Once parked: the trip's receipt (one line per routine, done or not and why) and the notices held during the drive,
     * each its own short notification with its heading as the title ([NoticeLines]: the centre greys out a body it has to
     * cut). What does not fit is counted; the app's receipt card has it all.
     */
    fun postDigest(released: NoticeHold.Released, receipt: List<String> = emptyList()) {
        if (receipt.isNotEmpty()) post(receipt, title = context.getString(R.string.receipt_title))
        if (released.isEmpty) return
        post(released.texts, title = context.getString(R.string.notify_digest_title), hidden = released.dropped)
    }

    /** A notice of the app's own, not of a routine: it waits for parking like any other. */
    fun tell(text: String) {
        val showNow = if (hold == null) text else hold.offer(text, quiet) ?: return
        post(showNow)
    }

    private fun post(text: String) = post(text.lines())

    private fun post(lines: List<String>, title: String = context.getString(R.string.app_name), hidden: Int = 0) {
        val body = NoticeLines.fit(lines, { context.getString(R.string.notify_more, it) }, hidden).joinToString("\n")
        val id = ids.incrementAndGet()
        val notification = Notification.Builder(context, Channels.ROUTINES)
            .setSmallIcon(R.drawable.ic_notification)
            .setGroup(NoticeText.group("notice", id))
            .setContentTitle(title)
            .setContentText(NoticeText.readable(body))
            .setStyle(Notification.BigTextStyle().bigText(NoticeText.readable(body)))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun text(message: NotifyMessage): String {
        val base = resolve(message.textRes)
        val action = message.action?.let { describe(it) } ?: return base
        val reason = message.reason?.let { resolve("reason_${it.name.lowercase()}") }
        return listOfNotNull(base, action, reason).joinToString(" · ")
    }

    private fun describe(action: Action): String? = when (action) {
        is Action.Write -> "${resolve("signal_" + action.signal.value.replace('.', '_'))} → ${action.value.label()}"
        is Action.SetDestination -> resolve("place_${action.placeId}")
        is Action.Notify, is Action.Remind, is Action.Wait -> null
    }

    private fun kr.routinegongbang.core.model.SignalValue.label(): String = when (this) {
        is kr.routinegongbang.core.model.SignalValue.Bool -> context.getString(if (value) R.string.value_on else R.string.value_off)
        is kr.routinegongbang.core.model.SignalValue.Num -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        is kr.routinegongbang.core.model.SignalValue.Text -> value
    }

    internal fun resolve(key: String): String {
        val id = context.resources.getIdentifier(key.replace('.', '_'), "string", context.packageName)
        return if (id != 0) context.getString(id) else key
    }

    private companion object {
        const val NOTIFY_BASE_ID = 1_000
    }
}

/**
 * Asks with a notification that has Yes / No buttons (parked only: while driving the planner asks for a voice
 * answer, which downgrades to NOTIFY until the STT SDK opens). The executor cancels after its confirmation budget,
 * which the notification counts down to; it is then removed.
 */
internal class NotificationConfirmer(private val context: Context, private val questions: Questions = Questions.app) : Confirmer {
    private val requests = AtomicInteger(0)
    private val texts = Texts(context)
    private val clock = ConfirmClock()

    override suspend fun confirm(request: ConfirmRequest): Boolean {
        val id = CONFIRM_BASE_ID + requests.incrementAndGet()
        val action = "${context.packageName}.CONFIRM.$id"
        // Before the receiver exists, so a failure here leaves nothing registered.
        val now = System.currentTimeMillis()
        val content = ConfirmContent.of(request, now, texts::action, deadlineMs = clock.deadline(request.routineId, now))
        val answer = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                answer.complete(intent.getBooleanExtra(EXTRA_YES, false))
            }
        }
        register(receiver, action)
        val manager = context.getSystemService(NotificationManager::class.java)
        return coroutineScope {
            // Also on the app's own screen: with the app in front, AAOS shows no heads-up. A voice-only
            // question belongs to driving, where the screen must not ask.
            val onScreen = if (request.voiceOnly) null else async { questions.ask(content) }
            try {
                // A notification that cannot be posted must not take the on-screen question with it.
                try {
                    manager.notify(id, build(content, action, id, now))
                } catch (e: RuntimeException) {
                    if (onScreen == null) throw e
                }
                select {
                    answer.onAwait { it }
                    onScreen?.onAwait { it }
                }
            } finally {
                onScreen?.cancel()
                manager.cancel(id)
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }

    private fun register(receiver: BroadcastReceiver, action: String) {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun build(content: ConfirmContent, action: String, id: Int, now: Long): Notification {
        fun pending(yes: Boolean) = PendingIntent.getBroadcast(
            context, id * 2 + if (yes) 1 else 0,
            Intent(action).setPackage(context.packageName).putExtra(EXTRA_YES, yes),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // The question stays on screen until answered: it must stay readable too (NoticeLines).
        val what = NoticeLines.fit(content.lines, { context.getString(R.string.notify_more, it) }).joinToString("\n")
        return Notification.Builder(context, Channels.ROUTINES)
            .setSmallIcon(R.drawable.ic_notification)
            .setGroup(NoticeText.group("confirm", id))
            .setContentTitle(context.getString(R.string.confirm_title, content.routineName))
            .setContentText(NoticeText.readable(what))
            .setStyle(Notification.BigTextStyle().bigText(NoticeText.readable(what)).setSummaryText(NoticeText.readable(context.getString(R.string.confirm_timeout_hint))))
            // Counts down to the moment the run gives up and does nothing.
            .setWhen(content.deadlineMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter((content.deadlineMs - now).coerceAtLeast(1))
            .addAction(Notification.Action.Builder(null, context.getString(R.string.confirm_yes), pending(true)).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.confirm_no), pending(false)).build())
            .setCategory(Notification.CATEGORY_REMINDER)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CONFIRM_BASE_ID = 5_000
        const val EXTRA_YES = "yes"
    }
}
