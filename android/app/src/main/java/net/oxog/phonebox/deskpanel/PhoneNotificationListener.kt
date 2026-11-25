package net.oxog.phonebox.deskpanel

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shows the phone's notifications on the Mac. Android keeps a notification listener alive itself, so nothing here needs a
 * foreground service: each notification is one short request. Only apps on the owner's list are sent ([NotificationFilter]),
 * nothing when the computer's switch is off, and what cannot be delivered waits in memory for two minutes ([DeliveryQueue]).
 * The text is never logged or stored.
 */
class PhoneNotificationListener : NotificationListenerService() {
    private val filter by lazy { NotificationFilter({ BridgePrefs.allowedApps(applicationContext) }, packageName, { defaultDialer() }, { BridgePrefs.allApps(applicationContext) }) }
    private val callGeneration = AtomicInteger()

    override fun onListenerConnected() {
        connected = true
        // a call that is already ringing or running when the listener (re)connects must not be sent again, as a new call or as a
        // rename of the one the Mac may already show
        try {
            val running = activeNotifications?.filter { isLifetimeCall(it.packageName, it.notification) }
                ?.map { it.key ?: "${it.packageName}|${it.id}|${it.tag}" }.orEmpty()
            onCalls { running.forEach { callLifetimes.running(it, System.currentTimeMillis()) } }
        } catch (_: Exception) {}
    }

    private fun isLifetimeCall(pkg: String, n: Notification?): Boolean =
        n != null && pkg in NotificationFilter.IN_CALL_UIS && n.category == "call" &&
            n.extras?.getString(Notification.EXTRA_TEMPLATE) == "android.app.Notification\$CallStyle"

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in NotificationFilter.IN_CALL_UIS) return
        val key = sbn.key ?: "${sbn.packageName}|${sbn.id}|${sbn.tag}"
        // the call ended: the next one is a new call. On the calls thread, after the updates still waiting there: one handled after
        // this would begin a call that never ends, and the next call under the same key would be taken for a repeat and not sent
        onCalls { callLifetimes.ended(key); contacts.forget() }
    }

    /** The name Android's call notification carries for the caller (a CallStyle's person), when it has one. */
    @Suppress("DEPRECATION")
    private fun callPersonName(sbn: StatusBarNotification): String? = try {
        if (Build.VERSION.SDK_INT >= 28) (sbn.notification?.extras?.getParcelable<android.app.Person>("android.callPerson"))?.name?.toString() else null
    } catch (_: Exception) { null }

    private fun defaultDialer(): String? = try {
        if (Build.VERSION.SDK_INT >= 23) (getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager)?.defaultDialerPackage else null
    } catch (_: Exception) { null }

    // Turning the switch off disables this component and Android unbinds it, not always through onListenerDisconnected (and
    // never below Android 7): without this the screen would say "allowed" after the switch comes back but before a rebind
    override fun onDestroy() {
        connected = false
        clearCallState()
        super.onDestroy()
    }

    override fun onListenerDisconnected() {
        connected = false
        clearCallState()
        // Android may drop the listener; ask to be bound again while the switch is on
        if (Build.VERSION.SDK_INT >= 24 && BridgePrefs.notificationsEnabled(this))
            requestRebind(ComponentName(this, PhoneNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = applicationContext
        val mark = queue.mark()
        val queuedAt = SystemClock.elapsedRealtime()
        if (!BridgePrefs.notificationsEnabled(app)) return
        val info = describe(sbn) ?: return
        val decision = filter.decide(info)
        // what decided a call (no title or text, only the app and the flags), once for each change: the call rule is the one that
        // differs between makers, and a diagnostic that repeats at every update of a call would fill the log. Off unless asked for
        // (adb shell setprop log.tag.PhoneBoxBridge DEBUG): it names the apps that had a call, listed or not, and the privacy page
        // says nothing about notifications is written to the phone's log
        if ((info.category == "call" || info.category == "missed_call" || info.callStyle) && android.util.Log.isLoggable("PhoneBoxBridge", android.util.Log.DEBUG)) {
            val line = "call notification from ${info.pkg}: category=${info.category} callStyle=${info.callStyle} ongoing=${info.ongoing} " +
                "callType=${sbn.notification?.extras?.getInt("android.callType", 0)} fullScreen=${sbn.notification?.fullScreenIntent != null} " +
                "actions=${sbn.notification?.actions?.size ?: 0} answerIntent=${sbn.notification?.extras?.containsKey("android.answerIntent")} -> $decision"
            if (line != lastDiagnostic) { lastDiagnostic = line; android.util.Log.d("PhoneBoxBridge", line) }
        }
        if (decision == Decision.SKIP) return
        val incomingCall = decision == Decision.SEND && info.category == "call"
        // the in-call UI's call stays posted for the whole call: sent once per notification, and again for the next call
        val lifetimeCall = incomingCall && info.callStyle && info.pkg in NotificationFilter.IN_CALL_UIS
        if (lifetimeCall) {
            val generation = callGeneration.get()
            val person = callPersonName(sbn)
            // off the main thread, where this callback runs: the contacts lookup and the default dialer are calls into other processes.
            // One thread, in order, with the removal and the reconnect: an older update handled after a newer one would rename the call back
            onCalls {
                fun current() = callGeneration.get() == generation &&
                    queue.isCurrent(mark) && BridgePrefs.notificationsEnabled(app)
                if (!current()) return@onCalls
                // the in-call UI first shows only the number: the person's name (from the notification, or the contacts) goes in its place,
                // and a later update that finally carries the name is sent again for the same call (the Mac replaces the first one)
                val named = info.copy(title = CallerName.pick(info.title, person) { contacts.get(it) })
                if (!current()) return@onCalls
                val shown = gatePackage(defaultDialer(), named.pkg)
                val callStart = callLifetimes.begin(named.key, System.currentTimeMillis(), named.title)
                    ?: callLifetimes.renamed(named.key, named.title) ?: return@onCalls
                // the next call from the same person must not look like a repeat to the Mac: its key carries the time this call began.
                // The in-call UI is not on the list and has no launcher icon (so no name or icon the phone may look up): the default
                // dialer stands for it, in what the Mac shows and in the check at send time
                enqueue(app, named, "${named.key}|$callStart", shown, "call", mark, queuedAt, generation)
            }
            return
        }
        // one call is sent once: answering, updating or ending it posts the same notification again
        if ((incomingCall || decision == Decision.SEND_AS_MISSED_CALL) && !callKeys.firstTime(info.key)) return
        val category = when { decision == Decision.SEND_AS_MISSED_CALL -> "missed_call"; incomingCall -> "call"; else -> "message" }
        // only the in-call UI's calls are shown as another app (above): no lookup of the default dialer for every message
        enqueue(app, info, info.key, info.pkg, category, mark, queuedAt)
    }

    private fun enqueue(app: Context, info: NotificationInfo, sendKey: String, shown: String, category: String,
                        mark: Int, queuedAt: Long, generation: Int? = null) {
        // cut here, as the agent does: a long email in a BigText notification must arrive shortened, not be refused whole
        val body = BridgeClip.notificationBody(sendKey, label(shown), shown, BridgeClip.cut(info.title, 200), BridgeClip.cut(info.text, 2000), category, info.time, AppIcons.base64(applicationContext, shown))
        val queued = synchronized(queue) {
            (generation == null || callGeneration.get() == generation) &&
                queue.addIfCurrent(NotificationDelivery.Pending(shown, body), mark, queuedAt)
        }
        if (queued) requestDrain(app)
    }

    private fun clearCallState() {
        synchronized(queue) { callGeneration.incrementAndGet() }
        onCalls { callLifetimes.clear(); contacts.forget() }
    }

    private fun onCalls(work: () -> Unit) {
        // an exception on a thread of our own would end the app's process, not only this notification
        calls.execute { try { work() } catch (_: Exception) {} }
    }

    private val contacts by lazy { LookupOnce { ContactNames.lookup(applicationContext, it) } }

    private fun describe(sbn: StatusBarNotification): NotificationInfo? {
        val n = sbn.notification ?: return null
        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        // CallStyle (Android 12+) says "incoming" itself (type 1); for a dialer without it, an incoming call is the one that
        // brings its own full-screen screen with two or more buttons (answer, decline)
        val callStyleIncoming = extras.getInt("android.callType", 0) == 1
        val answerAction = n.fullScreenIntent != null && (n.actions?.size ?: 0) >= 2
        val baseKey = sbn.key ?: "${sbn.packageName}|${sbn.id}|${sbn.tag}"
        // dialers post every call under one id and tag, so a call's key includes when it started: without that, only the first
        // call after the process started would ever be sent, here and on the agent (which de-duplicates by key and text)
        val callStyle = extras.getString(Notification.EXTRA_TEMPLATE) == "android.app.Notification\$CallStyle"
        val lifetimeCall = callStyle && n.category == "call" && sbn.packageName in NotificationFilter.IN_CALL_UIS
        val key = if (!lifetimeCall && (n.category == "call" || n.category == "missed_call")) "$baseKey|${n.`when`}" else baseKey
        return NotificationInfo(
            pkg = sbn.packageName, key = key,
            ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            category = n.category, callStyleIncoming = callStyleIncoming, hasAnswerAction = answerAction,
            title = title, text = text, time = sbn.postTime, callStyle = callStyle,
        )
    }

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    companion object {
        /** True while Android has this listener bound (notification access is allowed and the system started it). */
        @Volatile var connected = false

        private val worker = Executors.newSingleThreadExecutor()
        /** The in-call UI's call updates, removals and reconnects, in the order Android posted them (see [onCalls]). */
        private val calls = Executors.newSingleThreadExecutor()
        private val callKeys = CallKeys()
        private val callLifetimes = CallLifetimes()
        @Volatile private var lastDiagnostic = ""
        private val queue = DeliveryQueue<NotificationDelivery.Pending>(clock = SystemClock::elapsedRealtime)
        private val draining = AtomicBoolean(false)
        private val retryPending = AtomicBoolean(false)
        private var cache: Pair<String?, BridgeConfigCache>? = null

        /** Forgets what waits for the Mac: called when the switch is turned off, so nothing is sent afterwards. */
        fun clearPending() = queue.clear()

        /**
         * Another computer was chosen or one was forgotten in the panel: what waited was meant for the old one, and the
         * cached state of its switch says nothing about the new one (a cached "off" would drop notifications for a minute).
         */
        fun changeComputer(change: () -> Boolean): Boolean = synchronized(queue) {
            if (!change()) return@synchronized false
            cache = null
            queue.clear()
            BridgePrefs.notificationOutcome = NotificationOutcome.NONE
            true
        }

        /**
         * One drain at a time on one thread: a burst of notifications is one pass over the queue, and the queue's two-minute
         * limit applies to everything in it, so a slow or absent Mac cannot build a backlog that is sent late.
         */
        private fun requestDrain(app: Context) {
            if (!draining.compareAndSet(false, true)) return // the running pass picks up the new item
            worker.execute {
                var again: Boolean
                do {
                    val retryLater = try { drainOnce(app) } catch (_: Exception) { false }
                    draining.set(false)
                    if (retryLater) scheduleRetry(app)
                    again = !retryLater && !queue.isEmpty() && draining.compareAndSet(false, true)
                } while (again)
            }
        }

        private fun drainOnce(app: Context): Boolean {
            if (queue.isEmpty()) return false
            val mark = queue.mark()
            val link = AgentLink(app)
            try {
                val target = link.notificationTarget()
                val client = BridgeClient(link)
                val config = synchronized(queue) {
                    if (!queue.isCurrent(mark)) return false
                    cache?.takeIf { it.first == target.identity }?.second
                        ?: BridgeConfigCache(SystemClock::elapsedRealtime).also { cache = target.identity to it }
                }
                return NotificationDelivery(
                    queue,
                    phoneSwitchOn = { BridgePrefs.notificationsEnabled(app) },
                    appAllowed = { appAllowed(BridgePrefs.allApps(app), BridgePrefs.allowedApps(app), it) },
                    macSwitchOn = { config.notificationsOn { client.fetchConfig(target) } },
                    send = { body -> client.sendNotification(target, body) },
                    onOutcome = { BridgePrefs.notificationOutcome = it },
                    markMacOff = config::markOff,
                    markMacOn = config::markOn,
                ).drain(mark)
            } finally { link.close() }
        }

        private fun scheduleRetry(app: Context) {
            if (!retryPending.compareAndSet(false, true)) return
            Handler(Looper.getMainLooper()).postDelayed({
                retryPending.set(false)
                requestDrain(app)
            }, 30_000)
        }
    }
}
