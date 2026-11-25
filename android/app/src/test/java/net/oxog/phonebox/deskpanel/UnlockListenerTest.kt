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
        var onError: ((Long?) -> Unit)? = null
        override fun available() = available
        val order = mutableListOf<String>()
        override fun start(onTouch: (String) -> Unit, onMiss: () -> Unit, onError: (Long?) -> Unit): (() -> Unit)? {
            starts++
            order += "start"
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
        later = { ms, block -> delays += ms; deferredLater += block },
        tryNow = { quickCalls++; sensor.order += "quick"; quick },
        now = { clock },
    )
    private var clock = 0L
    private var quick: Boolean? = null
    private var quickCalls = 0
    private val deferredLater = mutableListOf<() -> Unit>()
    private val delays = mutableListOf<Long>()
    private fun runLater() { val l = deferredLater.toList(); deferredLater.clear(); l.forEach { it() } }

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
        sensor.onError!!(null)
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
        assertEquals(listOf("stopped", "stopped"), reports) // the repeated want re-reports the state
    }

    @Test fun wantOffDuringUnlockDoesNotRestart() {
        deferBackground = true
        on()
        deferred.removeAt(0)() // the quick try after the resume (returns null), then the sensor starts
        sensor.onTouch!!("sig")
        listener.want(false)
        deferred.forEach { it() }
        assertEquals(1, sensor.starts)
    }

    @Test fun repeatedWantReReportsStopped() {
        on()
        sensor.onError!!(null)
        listener.want(true)
        assertEquals(listOf("listening", "stopped", "stopped"), reports)
    }

    @Test fun repeatedWantReReportsListening() {
        on()
        listener.want(true)
        assertEquals(listOf("listening", "listening"), reports)
        assertEquals(1, sensor.starts)
    }

    @Test fun transientErrorRetriesLater() {
        on()
        sensor.onError!!(0L)
        assertEquals(listOf("listening"), reports)
        runLater()
        assertEquals(2, sensor.starts)
    }

    @Test fun threeTransientErrorsInARowStop() {
        on()
        repeat(3) { sensor.onError!!(0L); runLater() }
        assertEquals(listOf("listening", "listening", "listening", "stopped"), reports)
    }

    @Test fun lateCallbacksOfACancelledSessionAreIgnored() {
        on()
        val oldTouch = sensor.onTouch!!
        val oldError = sensor.onError!!
        listener.visible(false); listener.visible(true)
        oldTouch("sig"); oldError(null)
        assertEquals(listOf("listening", "listening"), reports)
        assertEquals(2, sensor.starts)
    }

    @Test fun resumeTriesWithoutTheSensorFirst() {
        quick = true
        on()
        assertEquals(listOf("quick", "start"), sensor.order)
        assertEquals(listOf("unlocked", "listening"), reports)
    }

    @Test fun quickTryNullStartsTheSensorSilently() {
        on()
        assertEquals(1, quickCalls)
        assertEquals(listOf("listening"), reports)
        assertEquals(1, sensor.starts)
    }

    @Test fun quickTryFalseReportsFailedThenListens() {
        quick = false
        on()
        assertEquals(listOf("failed", "listening"), reports)
    }

    @Test fun wantSoonAfterResumeTriesQuick() {
        listener.visible(true)
        clock = 3000
        listener.want(true)
        assertEquals(1, quickCalls)
    }

    @Test fun noQuickTryWhenWantArrivesLongAfterResume() {
        listener.visible(true)
        clock = 6000
        listener.want(true)
        assertEquals(0, quickCalls)
        assertEquals(1, sensor.starts)
    }

    @Test fun oneQuickTryPerResume() {
        on()
        listener.visible(false)
        clock = 100
        listener.visible(true)
        assertEquals(2, quickCalls)
        sensor.onTouch!!("sig")
        assertEquals(2, quickCalls)
        assertEquals(3, sensor.starts)
    }

    @Test fun lockoutRetriesAfterThirtySeconds() {
        on()
        sensor.onError!!(30_000L)
        assertEquals("stopped", reports.last())
        assertEquals(listOf(30_000L), delays)
        runLater()
        assertEquals(2, sensor.starts)
        assertEquals("listening", reports.last())
    }

    @Test fun lockoutRetryCancelledByWantOff() {
        on()
        sensor.onError!!(30_000L)
        listener.want(false)
        runLater()
        assertEquals(1, sensor.starts)
    }
}
