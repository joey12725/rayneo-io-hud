package io.lampyr.glasses.data

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.lampyr.glasses.service.GlassesService
import kotlinx.coroutines.*

/**
 * Phone notifications into the routing contract (see [NotificationRules]). Only allow-listed packages (Beeper,
 * T3 Code, dating apps) leave this method; everything else is suppressed here with no network call. The decision
 * (suppress / stream / popup) is made by [LiveDataStore.phoneNotification]; nothing here navigates or wakes.
 */
class PhoneNotifications : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.notification.category == Notification.CATEGORY_CALL) {
            val extras = sbn.notification.extras
            val person = if (android.os.Build.VERSION.SDK_INT >= 31) {
                @Suppress("DEPRECATION")
                (extras.getParcelable<android.app.Person>(Notification.EXTRA_CALL_PERSON))?.name?.toString()
            } else null
            val caller = person ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val dialer = getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage
            if (sbn.packageName == dialer && caller.isNotBlank())
                (GlassesService.instance?.ui?.data?.calls as? io.lampyr.glasses.calls.AndroidCalls)?.notificationCaller(caller)
            return
        }
        // Google Clock: its timers and ringing alarms are imported into the glasses Clock app (requirement 7).
        if (sbn.packageName == io.lampyr.glasses.clock.ClockNotifications.PACKAGE) { io.lampyr.glasses.clock.ClockBridge.posted(this, sbn); return }
        val input = input(sbn) ?: return
        val store = GlassesService.instance?.ui?.data as? LiveDataStore
        if (store == null) { io.lampyr.glasses.debug.Trace.log("Notify", "pkg=${sbn.packageName} dropped reason=service-down"); return }
        scope.launch { store.phoneNotification(sbn.key, input, sbn.postTime.takeIf { it > 0 }) }
    }

    /** The same routing input onNotificationPosted builds (debug bridge /notify/active checks real notifications with it). */
    internal fun routingInput(sbn: StatusBarNotification): NoticeInput? = input(sbn)

    /** Builds the routing input, or null when the notification is dropped locally (not allow-listed, summary, ongoing, empty). */
    private fun input(sbn: StatusBarNotification): NoticeInput? {
        val n = sbn.notification
        if (!NotificationRules.allowedPackage(sbn.packageName)) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return null
        if (n.category in setOf(Notification.CATEGORY_PROGRESS, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_SERVICE, Notification.CATEGORY_SYSTEM, Notification.CATEGORY_NAVIGATION)) return null
        val extras = n.extras
        val style = androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = style?.messages?.lastOrNull()?.text?.toString() ?: (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && body.isBlank()) return null
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val conversation = style?.conversationTitle?.toString() ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        val sender = style?.messages?.lastOrNull()?.person?.name?.toString().orEmpty()
        val labels = listOf(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(), title, conversation).joinToString(" ")
        val network = Regex("google messages|imessage|sms|instagram|whatsapp|discord|signal|telegram|messenger|slack", RegexOption.IGNORE_CASE).find(labels)?.value.orEmpty()
        val group = style?.isGroupConversation == true || extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        return NoticeInput(
            kind = NotificationRules.kindFor(sbn.packageName), source = sbn.packageName, app = app, title = title, body = body,
            conversationTitle = GatewayMappings.scrubIds(conversation), sender = GatewayMappings.safeName(sender), network = network, isGroup = group,
            channelId = n.channelId.orEmpty(), category = n.category.orEmpty(),
        )
    }

    /** The app withdrew it (read on the phone, chat opened): drop the glasses row too, without recording a dismissal. */
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        if (sbn.packageName == io.lampyr.glasses.clock.ClockNotifications.PACKAGE) { io.lampyr.glasses.clock.ClockBridge.removed(this, sbn); return }
        if (reason != REASON_APP_CANCEL && reason != REASON_CANCEL && reason != REASON_CLICK) return
        if (!NotificationRules.allowedPackage(sbn.packageName)) return
        val store = GlassesService.instance?.ui?.data as? LiveDataStore ?: return
        scope.launch { store.withdrawPhoneNotification(sbn.key) }
    }

    override fun onListenerConnected() {
        instance = this
        (GlassesService.instance?.ui?.data as? LiveDataStore)?.mediaHub?.refresh()
        runCatching { io.lampyr.glasses.clock.ClockBridge.seed(this, activeNotifications) }
    }
    override fun onListenerDisconnected() { if (instance === this) instance = null; super.onListenerDisconnected() }
    override fun onDestroy() { if (instance === this) instance = null; scope.cancel(); super.onDestroy() }

    companion object {
        /** The connected listener, for read-only debug checks of the currently posted notifications. */
        @Volatile var instance: PhoneNotifications? = null
            private set
    }
}
