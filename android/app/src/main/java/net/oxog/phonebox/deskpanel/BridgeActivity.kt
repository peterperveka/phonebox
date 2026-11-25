package net.oxog.phonebox.deskpanel

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * The phone's "Phone bridge" screen: the clipboard switch, a short how-to, and an honest status line (whether the
 * service is connected, or the computer's switch is off), so a feature that is switched on never looks like it works
 * when it is not. Opens from the panel, the clipboard service's notification and a long press on the Send to Mac tile.
 */
class BridgeActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var access: TextView
    private lateinit var accessDot: View
    private lateinit var last: TextView
    private lateinit var lastDot: View
    private lateinit var restrictedHelp: TextView
    private lateinit var appInfoButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val theme by lazy { BridgeTheme(this) }
    private fun dp(v: Int) = theme.dp(v)
    private val textColor get() = theme.text
    private val mutedColor get() = theme.muted
    private val accent get() = theme.accent
    private val good get() = theme.good
    private val wait get() = theme.wait
    private val bad get() = theme.bad
    private val grey get() = theme.grey

    private val refresh = object : Runnable {
        override fun run() {
            val clip = statusState()
            status.text = clip.first
            paint(statusDot, clip.second)
            val accessText = when {
                !notificationAccessAllowed() -> R.string.bridge_access_missing
                // allowed, switch on, but Android has not bound the listener (Samsung's battery management can unbind it)
                BridgePrefs.notificationsEnabled(this@BridgeActivity) && !PhoneNotificationListener.connected -> R.string.bridge_access_idle
                else -> R.string.bridge_access_granted
            }
            access.text = getString(accessText)
            paint(accessDot, when (accessText) { R.string.bridge_access_granted -> good; R.string.bridge_access_idle -> wait; else -> bad })
            // the restricted-settings hint is only for a person who cannot switch the access on
            val needHelp = accessText == R.string.bridge_access_missing
            restrictedHelp.visibility = if (needHelp) View.VISIBLE else View.GONE
            appInfoButton.visibility = if (needHelp) View.VISIBLE else View.GONE
            val outcome = BridgePrefs.notificationOutcome
            last.text = getString(when (outcome) {
                NotificationOutcome.NONE -> R.string.bridge_last_none
                NotificationOutcome.OK -> R.string.bridge_last_ok
                NotificationOutcome.MAC_OFF -> R.string.bridge_last_off
                NotificationOutcome.UNREACHABLE -> R.string.bridge_last_failed
            })
            paint(lastDot, when (outcome) { NotificationOutcome.OK -> good; NotificationOutcome.NONE -> grey; NotificationOutcome.MAC_OFF -> bad; NotificationOutcome.UNREACHABLE -> wait })
            handler.postDelayed(this, 1000)
        }
    }

    private fun paint(dot: View, color: Int) { dot.background = theme.dot(color) }

    private fun roundRect(color: Int, radiusDp: Int, strokeColor: Int? = null) = theme.roundRect(color, radiusDp, strokeColor)

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.1f)
    }

    private fun card(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(theme.card, 16, theme.cardStroke)
        setPadding(dp(16), dp(14), dp(16), dp(16))
        views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) topMargin = dp(10) }) }
    }

    /** A coloured dot and a line of text: the state of something, readable at a glance. */
    private fun statusRow(dotOut: (View) -> Unit, textOut: (TextView) -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val dot = View(context)
        dotOut(dot)
        addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) }) // end, not right: the gap stays by the text in Arabic
        addView(label("", 14f, textColor).also(textOut), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(0xFFFFFFFF.toInt())
        background = theme.pressable(roundRect(accent, 12))
        stateListAnimator = null
        minimumHeight = dp(48)
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(theme.accentText)
        background = theme.pressable(roundRect(0x00000000, 12, theme.accentText))
        stateListAnimator = null
        minimumHeight = dp(48)
        setOnClickListener { onClick() }
    }

    private fun switchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = title
        textSize = 17f
        setTextColor(textColor)
        typeface = Typeface.DEFAULT_BOLD
        minimumHeight = dp(48)
        isChecked = checked
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // the manifest's theme is the dark Material one; in light mode its switches (a white track) all but vanish on the white cards
        if (!theme.dark) setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        if (isOtherTilesLongPress(intent)) {
            // Android sends the long press on any of the app's tiles here once the filter exists: the PhoneBox tile's goes to
            // App info, which is what Android opened for it before the bridge (and still does while the bridge is off)
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: RuntimeException) {}
            finish()
            return
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        fun add(view: View, top: Int = 0) = column.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })
        add(label(getString(R.string.bridge_title), 28f, textColor, bold = true))

        // ---- the clipboard
        add(card(
            switchRow(getString(R.string.bridge_clipboard_switch), BridgePrefs.clipboardEnabled(this)) { on ->
                BridgePrefs.setClipboardEnabled(this, on)
                if (on && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                ClipboardService.sync(this)
            },
            statusRow({ statusDot = it }, { status = it }),
            label(getString(R.string.bridge_clipboard_help), 13f, mutedColor),
        ), 16)

        // ---- notifications on the Mac
        restrictedHelp = label(getString(R.string.bridge_restricted_help), 13f, mutedColor)
        appInfoButton = secondaryButton(getString(R.string.bridge_app_info_button)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        add(card(
            switchRow(getString(R.string.bridge_notifications_switch), BridgePrefs.notificationsEnabled(this)) { on ->
                BridgePrefs.setNotificationsEnabled(this, on)
                BridgeEntryPoints.update(this) // the listener shows in the system's Notification access list only now
            },
            label(getString(R.string.bridge_notifications_help), 13f, mutedColor),
            statusRow({ accessDot = it }, { access = it }),
            primaryButton(getString(R.string.bridge_access_button)) { openNotificationAccess() },
            restrictedHelp,
            appInfoButton,
            statusRow({ lastDot = it }, { last = it }),
        ), 12)
        // ---- the apps whose notifications are sent, right here
        val apps = BridgeAppsCard(this, theme)
        add(apps.view, 12)
        apps.load()
        // the scroll view, not the column, keeps clear of the bars: with the padding on the column the cards scroll up under the clock
        val scroll = ScrollView(this).apply { setBackgroundColor(theme.page); isFillViewport = true; addView(column) }
        theme.keepClearOfSystemBars(scroll)
        setContentView(scroll)
        // Android 15 draws the bars see-through over the page: in light mode their icons must be dark, or the clock is white on white
        if (Build.VERSION.SDK_INT >= 35 && !theme.dark) {
            val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(light, light)
        }
    }

    override fun onResume() {
        super.onResume()
        // Android may have stopped the service (the Task Manager, an update): opening the screen brings it back
        if (BridgePrefs.clipboardEnabled(this) && BridgePrefs.clipboardState == ClipboardState.STOPPED) ClipboardService.sync(this)
        askForContactsOnce()
        handler.post(refresh)
    }

    /** With notifications on, Android's own question about Contacts, once: a ringing call then shows the contact's name on the Mac. */
    private fun askForContactsOnce() {
        if (Build.VERSION.SDK_INT < 23 || !BridgePrefs.notificationsEnabled(this) || ContactNames.allowed(this) || BridgePrefs.askedContacts(this)) return
        BridgePrefs.setAskedContacts(this)
        requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 2)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    /** A tile long press (QS_TILE_PREFERENCES names the tile in EXTRA_COMPONENT_NAME) for a tile other than Send to Mac. */
    private fun isOtherTilesLongPress(intent: Intent?): Boolean {
        if (intent?.action != "android.service.quicksettings.action.QS_TILE_PREFERENCES") return false
        // Intent.EXTRA_COMPONENT_NAME by value: public from Android 8, the tiles of Android 7 already send it under this name
        val key = "android.intent.extra.COMPONENT_NAME"
        val tile = try {
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, ComponentName::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<ComponentName>(key)
        } catch (_: RuntimeException) { null } // exported: an extra that cannot be read counts as another tile
        return tile == null || tile.packageName != packageName || tile.className != "net.oxog.phonebox.deskpanel.SendClipboardTile"
    }

    // the setting is "pkg/Class:pkg/Class": compare whole package names, so another app whose name starts with ours does not count
    private fun notificationAccessAllowed(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.split(':')
            ?.any { ComponentName.unflattenFromString(it)?.packageName == packageName } == true

    private fun openNotificationAccess() {
        try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    /** The clipboard line and the colour of its dot: green when it works, amber while it tries, red when something must be done, grey when off. */
    private fun statusState(): Pair<String, Int> {
        if (!BridgePrefs.clipboardEnabled(this)) return getString(R.string.bridge_state_off) to grey
        return when (BridgePrefs.clipboardState) {
            ClipboardState.RUNNING -> getString(R.string.bridge_state_running) to good
            ClipboardState.MAC_OFF -> getString(R.string.bridge_state_mac_off) to bad
            ClipboardState.UNSUPPORTED -> getString(R.string.bridge_state_unsupported) to bad
            ClipboardState.WAITING -> getString(R.string.bridge_state_waiting) to wait
            ClipboardState.PAIR_AGAIN -> getString(R.string.bridge_pair_again) to bad
            ClipboardState.STOPPED -> (getString(R.string.bridge_state_stopped) + (BridgePrefs.lastError?.let { " ($it)" } ?: "")) to bad
        }
    }
}
