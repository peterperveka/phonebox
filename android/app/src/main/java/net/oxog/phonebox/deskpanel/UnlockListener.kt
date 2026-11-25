package net.oxog.phonebox.deskpanel

/**
 * Touch to unlock the PC screen: decides when the fingerprint sensor is listened to and restarts it after each touch.
 * It listens while the panel wants it (the PC is locked) and the panel is visible, unless Android stopped the sensor
 * (e.g. lockout after too many wrong fingers); the next "want" after a "don't want" tries again.
 *
 * A touch ends the session; [unlock] then runs on [background] with the authenticated signer and returns true
 * (unlocked), false (refused or unreachable) or null (nothing to unlock any more). Results go to [report]:
 * "listening", "unlocked", "failed", "stopped". All public calls and sensor callbacks must arrive on the main thread;
 * [main] brings the [unlock] result back there.
 *
 * A repeated want(true) re-reports the current state, so a panel that reloaded learns it again. A sensor error is
 * either final (lockout, no fingerprints: stopped) or transient (cancelled by the system, hardware busy): a transient
 * one retries through [later], and the third in a row without a touch stops.
 *
 * Quick try: when the listener becomes active within [QUICK_MS] of the panel coming back to the front, it first runs
 * [tryNow] (on [background]) without the sensor: the phone's own fingerprint unlock may already have opened the key.
 * true/false report "unlocked"/"failed", null (no recent fingerprint) reports nothing; the sensor starts afterwards.
 * Only right after a resume; [tryNow] also checks that the PC was already locked before that resume, so a fingerprint
 * used for something else just before the PC locks never unlocks it.
 */
class UnlockListener<S>(
    private val sensor: Sensor<S>,
    private val unlock: (S) -> Boolean?,
    private val report: (String) -> Unit,
    private val background: (() -> Unit) -> Unit,
    private val main: (() -> Unit) -> Unit,
    private val later: (() -> Unit) -> Unit,
    private val tryNow: () -> Boolean?,
    private val now: () -> Long,
) {
    interface Sensor<S> {
        fun available(): Boolean
        /** Starts one session; returns a cancel function, or null if it could not start. */
        fun start(onTouch: (S) -> Unit, onMiss: () -> Unit, onError: (stop: Boolean) -> Unit): (() -> Unit)?
    }

    private var wanted = false
    private var shown = false
    private var stopped = false
    private var busy = false
    private var cancel: (() -> Unit)? = null
    private var session = 0
    private var transient = 0
    private var resumedAt = Long.MIN_VALUE
    private var quickUsed = true

    fun want(on: Boolean) {
        if (on && wanted) {
            if (stopped) report("stopped") else if (cancel != null) report("listening")
        }
        if (!on) { stopped = false; transient = 0 }
        wanted = on
        reconsider()
    }

    fun visible(on: Boolean) {
        if (on && !shown) { resumedAt = now(); quickUsed = false }
        shown = on
        reconsider()
    }

    private fun reconsider() {
        if (!(wanted && shown && !stopped)) {
            cancel?.invoke()
            cancel = null
            return
        }
        if (cancel != null || busy) return
        if (!quickUsed && now() - resumedAt <= QUICK_MS) return quickTry()
        if (!sensor.available()) return stop()
        val id = ++session
        val c = sensor.start(
            onTouch = { s -> if (id == session && cancel != null) touched(s) },
            onMiss = {},
            onError = { final -> if (id == session && cancel != null) errored(final) },
        ) ?: return stop()
        cancel = c
        report("listening")
    }

    private fun quickTry() {
        quickUsed = true
        busy = true
        background {
            val r = try { tryNow() } catch (_: Exception) { null }
            main {
                busy = false
                when (r) {
                    true -> report("unlocked")
                    false -> report("failed")
                    null -> {}
                }
                reconsider()
            }
        }
    }

    private fun errored(final: Boolean) {
        cancel = null
        if (final || ++transient >= MAX_TRANSIENT) return stop()
        later { reconsider() }
    }

    private fun touched(s: S) {
        cancel = null
        transient = 0
        busy = true
        background {
            val r = try { unlock(s) } catch (_: Exception) { false }
            main {
                busy = false
                when (r) {
                    true -> report("unlocked")
                    false -> report("failed")
                    null -> {}
                }
                reconsider()
            }
        }
    }

    private fun stop() {
        stopped = true
        report("stopped")
    }

    private companion object {
        const val MAX_TRANSIENT = 3
        const val QUICK_MS = 5_000L
    }
}
