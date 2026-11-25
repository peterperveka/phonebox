package net.oxog.phonebox.deskpanel

import android.content.Context

/**
 * The phone's own bridge switches ("Phone bridge" screen). A feature runs only when this switch and the one on the
 * computer (Settings › Phone bridge) are both on; the computer's switch is enforced by the agent.
 */
object BridgePrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    fun clipboardEnabled(context: Context): Boolean = prefs(context).getBoolean("clipboard", false)

    fun setClipboardEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("clipboard", on).apply()
    }

    /** The bridge's pieces (Send to Mac entry points, boot start, notification listener) are switched on: set by a sync with a switch on, cleared by the clean-up. */
    fun live(context: Context): Boolean = prefs(context).getBoolean("live", false)

    fun setLive(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("live", on).apply()
    }

    fun notificationsEnabled(context: Context): Boolean = prefs(context).getBoolean("notifications", false)

    /** Turning it on for the first time also lists the default dialer and SMS app (and nothing else) as the apps to show. */
    fun setNotificationsEnabled(context: Context, on: Boolean) {
        val p = prefs(context)
        val edit = p.edit().putBoolean("notifications", on)
        if (on && !p.contains("apps")) edit.putStringSet("apps", defaultApps(context))
        edit.apply()
        if (!on) PhoneNotificationListener.clearPending() // what was waiting for the Mac must not be sent after the switch is off
    }

    /** Whether the phone has asked for Contacts already: it asks once, and the owner can still allow it in Android's settings. */
    fun askedContacts(context: Context): Boolean = prefs(context).getBoolean("askedContacts", false)

    fun setAskedContacts(context: Context) {
        prefs(context).edit().putBoolean("askedContacts", true).apply()
    }

    /** "All apps" on the Phone bridge screen: every app's notifications go to the Mac, whatever the list says. Off until the owner turns it on. */
    fun allApps(context: Context): Boolean = prefs(context).getBoolean("allApps", false)

    fun setAllApps(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("allApps", on).apply()
    }

    /** The apps whose notifications go to the Mac. */
    fun allowedApps(context: Context): Set<String> = prefs(context).getStringSet("apps", emptySet()) ?: emptySet()

    fun setAllowedApps(context: Context, apps: Set<String>) {
        prefs(context).edit().putStringSet("apps", HashSet(apps)).apply()
    }

    /** The phone's own dialer and default messaging app: calls and texts are what people want on the Mac first. */
    fun defaultApps(context: Context): Set<String> {
        val apps = HashSet<String>()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23)
                (context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager)?.defaultDialerPackage?.let { apps.add(it) }
        } catch (_: Exception) {}
        try { android.provider.Telephony.Sms.getDefaultSmsPackage(context)?.let { apps.add(it) } } catch (_: Exception) {}
        return apps
    }

    /** How the last notification went, for the screen. In memory only. */
    @Volatile var notificationOutcome: NotificationOutcome = NotificationOutcome.NONE
    @Volatile var clipboardState: ClipboardState = ClipboardState.STOPPED

    /** The last thing that went wrong starting the service (a class name, never clipboard text), shown on the screen. */
    @Volatile var lastError: String? = null
}
