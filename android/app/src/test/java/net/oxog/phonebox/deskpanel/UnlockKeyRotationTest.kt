package net.oxog.phonebox.deskpanel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockKeyRotationTest {
    /** In-memory stand-in for the Keystore aliases plus the "active alias" preference. */
    private class FakeSlots(var active: String) : UnlockKeyRotation.Slots {
        val keys = mutableMapOf<String, ByteArray>()
        var failCreate = false
        var failSave = false
        override fun activeAlias() = active
        override fun setActiveAlias(alias: String) { check(!failSave); active = alias }
        override fun create(alias: String): ByteArray {
            if (failCreate) throw IllegalStateException("no fingerprint enrolled")
            return byteArrayOf(alias.length.toByte(), keys.size.toByte()).also { keys[alias] = it }
        }
        override fun delete(alias: String) { keys.remove(alias) }
    }

    private val working = byteArrayOf(9, 9)

    private fun slots(active: String = UnlockKeyRotation.LEGACY) = FakeSlots(active).apply { keys[active] = working }

    @Test fun spareIsNeverTheActiveAlias() {
        assertEquals(UnlockKeyRotation.A, UnlockKeyRotation.spare(UnlockKeyRotation.LEGACY))
        assertEquals(UnlockKeyRotation.B, UnlockKeyRotation.spare(UnlockKeyRotation.A))
        assertEquals(UnlockKeyRotation.A, UnlockKeyRotation.spare(UnlockKeyRotation.B))
    }

    @Test fun failedEnrollKeepsTheWorkingKey() {
        val s = slots()
        assertNull(UnlockKeyRotation.rotate(s) { false })
        assertEquals(UnlockKeyRotation.LEGACY, s.active)
        assertArrayEquals(working, s.keys[UnlockKeyRotation.LEGACY])
        assertEquals(setOf(UnlockKeyRotation.LEGACY), s.keys.keys)
    }

    @Test fun enrollThatThrowsKeepsTheWorkingKey() {
        val s = slots(UnlockKeyRotation.A)
        assertNull(UnlockKeyRotation.rotate(s) { throw java.io.IOException("offline") })
        assertEquals(UnlockKeyRotation.A, s.active)
        assertEquals(setOf(UnlockKeyRotation.A), s.keys.keys)
    }

    @Test fun failedCreateKeepsTheWorkingKey() {
        val s = slots().apply { failCreate = true }
        assertNull(UnlockKeyRotation.rotate(s) { true })
        assertEquals(UnlockKeyRotation.LEGACY, s.active)
        assertArrayEquals(working, s.keys[UnlockKeyRotation.LEGACY])
    }

    @Test fun unsavedPointerKeepsTheWorkingKey() {
        val s = slots().apply { failSave = true }
        assertNull(UnlockKeyRotation.rotate(s) { true })
        assertEquals(UnlockKeyRotation.LEGACY, s.active)
        assertEquals(setOf(UnlockKeyRotation.LEGACY), s.keys.keys)
    }

    @Test fun enrolledKeyReplacesTheOldOne() {
        val s = slots(UnlockKeyRotation.A)
        var sent: ByteArray? = null
        val spki = UnlockKeyRotation.rotate(s) { sent = it; true }
        assertNotNull(spki)
        assertArrayEquals(sent, spki)
        assertEquals(UnlockKeyRotation.B, s.active)
        assertEquals(setOf(UnlockKeyRotation.B), s.keys.keys)
        assertNotNull(UnlockKeyRotation.rotate(s) { true })
        assertEquals(UnlockKeyRotation.A, s.active)
    }

    @Test fun onlyAConfirmedMissingKeyReportsNoCode() {
        val spki = byteArrayOf(1, 2, 3)
        assertEquals("", UnlockKeyRotation.code(UnlockKeyRotation.State.MISSING, null))
        assertEquals("", UnlockKeyRotation.code(UnlockKeyRotation.State.INVALIDATED, null))
        assertEquals(UnlockKeyRotation.UNKNOWN, UnlockKeyRotation.code(UnlockKeyRotation.State.ERROR, null))
        assertEquals(UnlockChallenge.checkCode(spki), UnlockKeyRotation.code(UnlockKeyRotation.State.OK, spki))
        assertEquals(UnlockKeyRotation.UNKNOWN, UnlockKeyRotation.code(UnlockKeyRotation.State.OK, null))
        assertTrue(UnlockKeyRotation.UNKNOWN.isNotEmpty())
    }

    @Test fun outdatedKeyCountsAsMissing() {
        assertEquals("", UnlockKeyRotation.code(UnlockKeyRotation.State.OUTDATED, byteArrayOf(1)))
    }
}
