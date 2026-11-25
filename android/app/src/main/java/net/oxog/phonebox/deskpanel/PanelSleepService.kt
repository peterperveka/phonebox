package net.oxog.phonebox.deskpanel

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Lets the panel turn the phone screen off when the PC locks ("Follow the computer lock"), the way the power button
 * does: the keyguard locks and the fingerprint unlocks the next time. It listens to no events and reads nothing on the
 * screen (res/xml/panel_sleep_service.xml). The user turns it on once in Settings › Accessibility. Needed because some
 * phones (Xiaomi without a SIM) refuse the agent's injected sleep key, and over Wi-Fi there is no ADB at all.
 */
class PanelSleepService : AccessibilityService() {

    override fun onServiceConnected() {
        connected = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (connected === this) connected = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    /** Screen off and keyguard locked, like the power button (Android 9+). */
    fun lockNow(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    companion object {
        /** The running service while the user has it turned on, else null. */
        @Volatile var connected: PanelSleepService? = null
            private set
    }
}
