package net.oxog.phonebox.deskpanel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockChallengeTest {
    private val nonce = "0123456789abcdef".repeat(4)
    private val now = 1_000L

    private fun challenge(purpose: String, command: String) =
        "phonebox-unlock-v1|$purpose|peter|fedora|$command|$nonce|2000"

    private fun parts(purpose: String, command: String) = UnlockChallenge.parse(challenge(purpose, command))!!

    @Test fun pickScreenAcceptsAFreshScreenChallenge() {
        val c = challenge("screen", "-")
        assertEquals(c, UnlockChallenge.pickScreen(c, now))
    }

    @Test fun pickScreenRejectsExpiredOtherPurposeAndNull() {
        assertNull(UnlockChallenge.pickScreen(challenge("screen", "-"), 2000L))
        assertNull(UnlockChallenge.pickScreen(challenge("sudo", "ls"), now))
        assertNull(UnlockChallenge.pickScreen(null, now))
        assertNull(UnlockChallenge.pickScreen("", now))
    }

    @Test fun quickUnlockOnlyWhenThePcWasLockedBeforeThePhoneWoke() {
        assertTrue(UnlockChallenge.lockedBeforeResume(lockedForMs = 60_000, sinceResumeMs = 800))
        assertFalse(UnlockChallenge.lockedBeforeResume(lockedForMs = 500, sinceResumeMs = 3_500))
        assertFalse(UnlockChallenge.lockedBeforeResume(lockedForMs = null, sinceResumeMs = 800))
    }

    @Test fun parsesAGoodChallenge() {
        val p = UnlockChallenge.parse(challenge("sudo", "sudo dnf up"))
        assertEquals(UnlockChallenge.Parts("sudo", "peter", "fedora", "sudo dnf up", nonce, 2000L), p)
    }

    @Test fun rejectsWrongShapes() {
        assertNull(UnlockChallenge.parse("phonebox-unlock-v1|sudo|peter|fedora|ls|$nonce"))
        assertNull(UnlockChallenge.parse("phonebox-unlock-v2|sudo|peter|fedora|ls|$nonce|2000"))
        assertNull(UnlockChallenge.parse("phonebox-unlock-v1|ssh|peter|fedora|ls|$nonce|2000"))
        assertNull(UnlockChallenge.parse("phonebox-unlock-v1|sudo|peter|fedora|ls|${nonce.drop(1)}|2000"))
        assertNull(UnlockChallenge.parse("phonebox-unlock-v1|sudo|peter|fedora|ls|${nonce.uppercase()}|2000"))
        assertNull(UnlockChallenge.parse("phonebox-unlock-v1|sudo|peter|fedora|ls|$nonce|-5"))
        assertNull(UnlockChallenge.parse("phonebox-unlock-v1|sudo|peter|fedora|ls|$nonce|"))
        assertNull(UnlockChallenge.parse("phonebox-unlock-v1|sudo|peter|fedora|ls|$nonce|99999999999999999999"))
        assertNull(UnlockChallenge.parse("phonebox-unlock-v1|sudo|peter|fedora|ls|$nonce|2000|extra"))
    }

    @Test fun screenButtonRefusesSudoChallenge() {
        assertFalse(UnlockChallenge.acceptable(parts("sudo", "ls"), UnlockChallenge.MODE_SCREEN, now))
        assertFalse(UnlockChallenge.acceptable(parts("polkit", "-"), UnlockChallenge.MODE_SCREEN, now))
        assertTrue(UnlockChallenge.acceptable(parts("screen", "-"), UnlockChallenge.MODE_SCREEN, now))
    }

    @Test fun requestCardRefusesScreenChallenge() {
        assertFalse(UnlockChallenge.acceptable(parts("screen", "-"), UnlockChallenge.MODE_REQUEST, now))
        assertTrue(UnlockChallenge.acceptable(parts("sudo", "ls"), UnlockChallenge.MODE_REQUEST, now))
        assertTrue(UnlockChallenge.acceptable(parts("polkit", "-"), UnlockChallenge.MODE_REQUEST, now))
    }

    @Test fun unknownModeRefusesEverything() {
        assertFalse(UnlockChallenge.acceptable(parts("sudo", "ls"), "other", now))
    }

    @Test fun polkitPromptText() {
        val p = parts("polkit", "-")
        assertEquals("System authentication — peter@fedora", UnlockChallenge.promptTitle(p))
        assertNull(UnlockChallenge.promptDetail(p))
        assertEquals("Unlock PC — peter@fedora", UnlockChallenge.promptTitle(parts("screen", "-")))
    }

    @Test fun expiredIsRefused() {
        val p = parts("sudo", "ls")
        assertFalse(UnlockChallenge.acceptable(p, UnlockChallenge.MODE_REQUEST, 2000L))
        assertFalse(UnlockChallenge.acceptable(p, UnlockChallenge.MODE_REQUEST, 3000L))
        assertTrue(UnlockChallenge.acceptable(p, UnlockChallenge.MODE_REQUEST, 1999L))
    }

    @Test fun promptTextComesFromTheChallenge() {
        val p = parts("sudo", "sudo dnf up")
        assertEquals("sudo — peter@fedora", UnlockChallenge.promptTitle(p))
        assertEquals("sudo dnf up", UnlockChallenge.promptDetail(p))
    }

    @Test fun promptTextStripsControlAndFormatCharacters() {
        val p = UnlockChallenge.Parts("sudo", "pe‮ter", "fe\u0007dora", "rm ‮-rf​ /\n\u0000x", nonce, 2000L)
        assertEquals("sudo — peter@fedora", UnlockChallenge.promptTitle(p))
        assertEquals("rm -rf /x", UnlockChallenge.promptDetail(p))
    }

    // Fixed P-256 SPKI (generated with openssl); code = first 20 hex of SHA-256 (python hashlib), uppercase, groups of 4.
    // Same formula as C# UnlockChallenge.CheckCode.
    private val spkiHex = "3059301306072a8648ce3d020106082a8648ce3d03010703420004b0752312d28a51eddc83b1816f115407a41aed9cccb33d3b9b0c2d917b2e44d30b9af0100cf1789a3ea96bf81cca30896970621c79f554fc60e5359d5a4e3fd3"

    @Test fun checkCodeFormat() {
        val spki = ByteArray(spkiHex.length / 2) { spkiHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val code = UnlockChallenge.checkCode(spki)
        assertNotNull(code)
        assertTrue(Regex("^[0-9A-F]{4}( [0-9A-F]{4}){4}$").matches(code))
        assertEquals("6E08 9001 8B54 26CF 1567", code)
    }
}
