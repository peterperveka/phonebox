package net.oxog.phonebox.deskpanel

/**
 * Sends what is waiting in the [DeliveryQueue] to the Mac, one batch at a time, and decides what must never be sent:
 * nothing while the phone's switch is off or the Mac's is known to be off (what waits is dropped, not held for later),
 * nothing for an app the owner has since turned off, nothing older than the queue's two minutes, and nothing more after
 * the Mac answers 403. No Android types, so tools/test-bridge-checks.py runs it.
 */
class NotificationDelivery(
    private val queue: DeliveryQueue<Pending>,
    private val phoneSwitchOn: () -> Boolean,
    private val appAllowed: (String) -> Boolean,
    private val macSwitchOn: () -> Boolean,
    private val send: (String) -> SendResult,
    private val onOutcome: (NotificationOutcome) -> Unit,
    private val markMacOff: () -> Unit,
    private val markMacOn: () -> Unit,
) {
    /** A notification waiting to go: the posting app (checked again at send time) and the JSON body. */
    class Pending(val pkg: String, val body: String)

    /** Sends the fresh notifications in order. True when some are left to try again later (the Mac could not be reached). */
    fun drain(mark: Int = queue.mark()): Boolean {
        if (!queue.isCurrent(mark)) return false
        if (!phoneSwitchOn()) { queue.clearIfCurrent(mark); return false }
        if (!macSwitchOn()) {
            synchronized(queue) {
                if (queue.clearIfCurrent(mark)) onOutcome(NotificationOutcome.MAC_OFF)
            }
            return false
        }
        val waiting = queue.takeIfCurrent(mark) ?: return false
        var i = 0
        while (i < waiting.size) {
            val entry = waiting[i]
            // a batch can take a while: the switch, the app's place on the list and the age are looked at again for each notification
            if (!phoneSwitchOn()) { queue.clearIfCurrent(mark); return false }
            // the switch went off and on again while a request was out (or another computer was chosen): a switched-off
            // switch drops what waited, so the rest of this batch is void; what was posted since stays for the next pass
            if (!queue.isCurrent(mark)) return false
            if (!queue.isFresh(entry) || !appAllowed(entry.item.pkg)) { i++; continue }
            val result = send(entry.item.body)
            synchronized(queue) {
                if (!queue.isCurrent(mark)) return false
                when (result) {
                    SendResult.OK -> { markMacOn(); onOutcome(NotificationOutcome.OK); i++ }
                    SendResult.OFF -> {
                        queue.clear()
                        markMacOff()
                        onOutcome(NotificationOutcome.MAC_OFF)
                        return false
                    }
                    SendResult.TOO_LARGE -> i++ // the agent will never take it; dropped
                    SendResult.UNREACHABLE, SendResult.UNKNOWN, SendResult.PAIR_AGAIN -> {
                        onOutcome(NotificationOutcome.UNREACHABLE)
                        // Preserve the original times and put older events ahead of arrivals during the request.
                        return queue.restore(waiting.drop(i), mark)
                    }
                }
            }
        }
        return false
    }
}
