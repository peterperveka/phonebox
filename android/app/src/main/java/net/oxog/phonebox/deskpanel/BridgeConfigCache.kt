package net.oxog.phonebox.deskpanel

/**
 * Whether the computer's "Show phone notifications" switch is on, asked of the agent (/api/config) at most once a minute.
 * Not knowing counts as on: a notification is tried, the agent refuses it with 403 if the switch is off, and if the computer
 * cannot be reached the short delivery queue holds it. A known "off" stays off through an unreachable agent.
 */
class BridgeConfigCache(private val clock: () -> Long) {
    private var known: Boolean? = null
    private var nextAsk = 0L

    @Synchronized
    fun notificationsOn(fetch: () -> String?): Boolean {
        val now = clock()
        if (now >= nextAsk) {
            val answer = try { fetch()?.let { BridgeClip.notificationsOn(it) } } catch (_: Exception) { null }
            if (answer != null) { known = answer; nextAsk = now + ASK_AGAIN_MS } else nextAsk = now + RETRY_MS
        }
        return known ?: true
    }

    /** The agent answered a send with 403: it is off, whatever the last config said; ask again in a minute. */
    @Synchronized
    fun markOff() { known = false; nextAsk = clock() + ASK_AGAIN_MS }

    /** A send went through: it is on. */
    @Synchronized
    fun markOn() { known = true; nextAsk = clock() + ASK_AGAIN_MS }

    private companion object {
        const val ASK_AGAIN_MS = 60_000L
        const val RETRY_MS = 10_000L
    }
}
