package net.oxog.phonebox.deskpanel

/** What the filter needs to know about one posted notification. Built from a StatusBarNotification by the listener. */
data class NotificationInfo(
    val pkg: String,
    val key: String,
    val ongoing: Boolean,
    val groupSummary: Boolean,
    val category: String?,
    /** A CallStyle notification of type "incoming" (Android 12+). */
    val callStyleIncoming: Boolean,
    /** The notification offers an action that answers the call (for dialers that do not use CallStyle). */
    val hasAnswerAction: Boolean,
    val title: String,
    val text: String,
    val time: Long,
    /** The notification uses the CallStyle template, whatever its type (Samsung's in-call UI posts a ringing call as type "ongoing"). */
    val callStyle: Boolean = false,
)

enum class Decision { SKIP, SEND, SEND_AS_MISSED_CALL }

/**
 * Decides which notifications go to the Mac. Only apps on the owner's list are ever sent; PhoneBox's own, group summaries,
 * empty ones and ongoing ones (media, downloads, a call in progress) are not. A call is the exception to "ongoing": dialers
 * post an incoming call as ongoing, so an incoming one (CallStyle incoming, or an answer action) is sent.
 * Calls: an incoming one (CallStyle incoming, an answer action, or the Samsung in-call UI's CallStyle) is sent; see the checks.
 * Outgoing calls look the same on Samsung (same CallStyle, same "ongoing" type), so they are sent too.
 */
class NotificationFilter(
    private val allowed: () -> Set<String>, private val ownPackage: String, private val dialer: () -> String? = { null },
    /** The owner chose "all apps": the list is not consulted (PhoneBox's own, summaries, empty and ongoing ones are still left out). */
    private val allApps: () -> Boolean = { false },
) {
    companion object {
        /** In-call apps that post a call for the default dialer: the dialer's own package is not the one that rings. */
        val IN_CALL_UIS = setOf("com.samsung.android.incallui")
    }

    fun decide(n: NotificationInfo): Decision {
        if (n.pkg == ownPackage || n.groupSummary) return Decision.SKIP
        if (n.title.isBlank() && n.text.isBlank()) return Decision.SKIP
        // Samsung posts a call from its in-call UI (com.samsung.android.incallui), not from the dialer package, as a CallStyle
        // notification of type "ongoing" with only a Hang up button (recorded on a Galaxy S25 Ultra); whoever put the default dialer
        // on the list asked for calls. Only that in-call UI is let through on the dialer's say: any other app's CallStyle call
        // (WhatsApp, Signal, Meet) follows the list like every other notification.
        if (n.category == "call" && n.callStyle && n.pkg in IN_CALL_UIS && (allApps() || dialer()?.let { it in allowed() } == true)) return Decision.SEND
        if (!appAllowed(allApps(), allowed(), n.pkg)) return Decision.SKIP
        return when (n.category) {
            "missed_call" -> Decision.SEND_AS_MISSED_CALL
            "call" -> if (n.callStyleIncoming || n.hasAnswerAction) Decision.SEND else Decision.SKIP
            else -> if (n.ongoing) Decision.SKIP else Decision.SEND
        }
    }
}

/** Remembers which call notifications were already sent, so a call that is answered, updated or ended is not sent again. */
class CallKeys(private val max: Int = 50) {
    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun firstTime(key: String): Boolean {
        if (!seen.add(key)) return false
        while (seen.size > max) seen.remove(seen.first())
        return true
    }
}

/**
 * The app the list is checked against again when the notification is sent (the owner may have switched it off meanwhile), and the
 * one whose name and icon the Mac shows: for a call from the in-call UI that is the default dialer, which is what let the call
 * through (the in-call UI is not on the list, has no launcher icon and so no name the phone may look up); for anything else the app itself.
 */
/**
 * Whether an app's notifications may go to the Mac: "all apps", or the app is on the list. One rule for the filter and for the
 * check at send time ([NotificationDelivery]'s appAllowed), so what waits in the queue when the owner turns "all apps" off is
 * dropped exactly as the filter would now skip it.
 */
fun appAllowed(allApps: Boolean, allowed: Set<String>, pkg: String): Boolean = allApps || pkg in allowed

fun gatePackage(defaultDialer: String?, postingPackage: String): String =
    if (postingPackage in NotificationFilter.IN_CALL_UIS) (defaultDialer ?: postingPackage) else postingPackage

/**
 * Who is calling. Samsung's in-call notification shows the number until it knows better, and the phone's contacts are the
 * owner's own: a title that is only a number is replaced by the name the notification itself carries (its call person) or,
 * failing that, by the contact with that number. Anything that already reads as a name is left alone.
 */
object CallerName {
    /**
     * The text without invisible formatting characters: Android wraps a phone number in direction marks (U+200E, U+202A..U+202C,
     * U+2066..U+2069) so that it reads left to right in an Arabic or Hebrew phone, and with them the number neither looks like a
     * number nor matches a contact.
     */
    fun plain(s: String): String = s.filter { Character.getType(it) != Character.FORMAT.toInt() }.trim()

    /** Digits with the usual separators, at least three of them: "+30 694 093 1040", "(210) 555-1234", "030/1234567", "6940931040". */
    fun isNumberLike(s: String): Boolean {
        val t = plain(s)
        return t.count { it.isDigit() } >= 3 && t.all { it.isDigit() || it in "+-().#*/" || it.isWhitespace() }
    }

    fun pick(title: String, personName: String?, lookup: (String) -> String?): String {
        if (!isNumberLike(title)) return title
        if (!personName.isNullOrBlank() && !isNumberLike(personName)) return personName.trim()
        return lookup(plain(title))?.takeIf { it.isNotBlank() } ?: title
    }
}

/**
 * The contacts are asked once per number of a call, not at every update of its notification (an unknown number would otherwise be
 * looked up again each time the in-call UI updates it); forgotten when the call ends, so a contact saved meanwhile is found next time.
 */
class LookupOnce(private val lookup: (String) -> String?) {
    private var number: String? = null
    private var name: String? = null
    @Synchronized fun get(n: String): String? {
        if (n != number) { name = lookup(n); number = n }
        return name
    }
    @Synchronized fun forget() { number = null; name = null }
}

/** A call notification stays posted for the whole call and is updated many times: it is sent once, and again for the next call. */
class CallLifetimes {
    /** [title] is the one last sent; null for a call that was already running when the listener connected, which is never sent. */
    private class Call(val start: Long, var title: String?)
    private val live = HashMap<String, Call>()
    /** The time the call started (now) when this is its first update, null for the later ones. */
    @Synchronized fun begin(key: String, now: Long, title: String = ""): Long? = if (key in live) null else { live[key] = Call(now, title); now }
    /**
     * A call that was already posted when the listener (re)connected: it may have reached the Mac before, so neither its next
     * update nor a name that arrives for it is sent (a rename would go out under a new start time, as a second call on the Mac).
     */
    @Synchronized fun running(key: String, now: Long) { if (key !in live) live[key] = Call(now, null) }
    /** The start of the call when [title] is not the one last sent for it (the caller was named after the first ring), else null. */
    @Synchronized fun renamed(key: String, title: String): Long? {
        val call = live[key] ?: return null
        if (call.title == null || call.title == title) return null
        call.title = title
        return call.start
    }
    @Synchronized fun ended(key: String) { live.remove(key) }
    @Synchronized fun clear() = live.clear()
}
