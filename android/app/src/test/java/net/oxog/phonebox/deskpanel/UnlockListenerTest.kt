package net.oxog.phonebox.deskpanel

import org.junit.Assert.assertEquals
import org.junit.Test

class UnlockListenerTest {
    /** A sensor that records sessions and keeps the last session's callbacks so a test can fire them. */
    private class FakeSensor(var available: Boolean = true) : UnlockListener.Sensor<String> {
        var starts = 0
        var cancels = 0
        var onTouch: ((String) -> Unit)? = null
        var onMiss: (() -> Unit)? = null
        var onError: (() -> Unit)? = null
        override fun available() = available
        override fun start(onTouch: (String) -> Unit, onMiss: () -> Unit, onError: () -> Unit): (() -> Unit)? {
            starts++
            this.onTouch = onTouch; this.onMiss = onMiss; this.onError = onError
            return { cancels++ }
        }
    }

    private val sensor = FakeSensor()
    private val reports = mutableListOf<String>()
    private var result: Boolean? = true
    private val deferred = mutableListOf<() -> Unit>()
    private var deferBackground = false

    private val listener = UnlockListener(
        sensor = sensor,
        unlock = { _ -> result },
        report = { reports += it },
        background = { if (deferBackground) deferred += it else it() },
        main = { it() },
    )

    private fun on() { listener.want(true); listener.visible(true) }

    @Test fun listensOnlyWhenWantedAndVisible() {
        listener.want(true)
        assertEquals(0, sensor.starts)
        listener.visible(true)
        assertEquals(1, sensor.starts)
        assertEquals(listOf("listening"), reports)
    }

    @Test fun repeatedWantStartsOnce() {
        repeat(3) { listener.want(true) }
        repeat(2) { listener.visible(true) }
        assertEquals(1, sensor.starts)
    }

    @Test fun touchUnlocksAndListensAgain() {
        on()
        sensor.onTouch!!("sig")
        assertEquals(listOf("listening", "unlocked", "listening"), reports)
        assertEquals(2, sensor.starts)
    }

    @Test fun refusedReportsFailedAndListensAgain() {
        result = false
        on()
        sensor.onTouch!!("sig")
        assertEquals(listOf("listening", "failed", "listening"), reports)
    }

    @Test fun nothingToUnlockReportsNothing() {
        result = null
        on()
        sensor.onTouch!!("sig")
        assertEquals(listOf("listening", "listening"), reports)
    }

    @Test fun missKeepsTheSession() {
        on()
        sensor.onMiss!!()
        assertEquals(1, sensor.starts)
        assertEquals(0, sensor.cancels)
    }

    @Test fun invisibleCancelsAndVisibleRestarts() {
        on()
        listener.visible(false)
        assertEquals(1, sensor.cancels)
        listener.visible(true)
        assertEquals(2, sensor.starts)
    }

    @Test fun errorStopsUntilWantedAgain() {
        on()
        sensor.onError!!()
        assertEquals(listOf("listening", "stopped"), reports)
        listener.visible(false); listener.visible(true)
        assertEquals(1, sensor.starts)
        listener.want(false); listener.want(true)
        assertEquals(2, sensor.starts)
    }

    @Test fun unavailableSensorNeverStarts() {
        sensor.available = false
        on()
        listener.want(true)
        assertEquals(0, sensor.starts)
        assertEquals(listOf("stopped"), reports)
    }

    @Test fun wantOffDuringUnlockDoesNotRestart() {
        deferBackground = true
        on()
        sensor.onTouch!!("sig")
        listener.want(false)
        deferred.forEach { it() }
        assertEquals(1, sensor.starts)
    }
}
