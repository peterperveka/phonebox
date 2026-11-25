package net.oxog.phonebox.deskpanel

import java.security.MessageDigest

/**
 * Phone-side rules for unlock challenges (`phonebox-unlock-v1|purpose|user|host|command|nonce|expiresAtMs`).
 * The phone decides what it will sign, and what it tells the user, from the challenge bytes alone, never from
 * text the agent sends next to them. Mirrors the agent's C# `UnlockChallenge.Parse`.
 */
object UnlockChallenge {
    data class Parts(
        val purpose: String,
        val user: String,
        val host: String,
        val command: String,
        val nonce: String,
        val expiresAt: Long,
    )

    /** A sudo or polkit request card: accepts purposes `sudo` and `polkit`. */
    const val MODE_REQUEST = "request"

    /** The Unlock PC button: accepts only purpose `screen`. */
    const val MODE_SCREEN = "screen"

    private const val PREFIX = "phonebox-unlock-v1"

    /** Parses a challenge, or null if it is not exactly the expected shape. */
    fun parse(s: String): Parts? {
        val f = s.split('|')
        if (f.size != 7 || f[0] != PREFIX) return null
        if (f[1] != "sudo" && f[1] != "polkit" && f[1] != "screen") return null
        if (f[5].length != 64 || !f[5].all { it in '0'..'9' || it in 'a'..'f' }) return null
        if (f[6].isEmpty() || !f[6].all { it in '0'..'9' }) return null
        val expires = f[6].toLongOrNull() ?: return null
        return Parts(f[1], f[2], f[3], f[4], f[5], expires)
    }

    /** True if the phone may sign this challenge in [mode] at [nowMs]. */
    fun acceptable(p: Parts, mode: String, nowMs: Long): Boolean {
        val purposeOk = when (mode) {
            MODE_REQUEST -> p.purpose == "sudo" || p.purpose == "polkit"
            MODE_SCREEN -> p.purpose == "screen"
            else -> false
        }
        return purposeOk && p.expiresAt > nowMs
    }

    fun promptTitle(p: Parts): String {
        val who = "${clean(p.user)}@${clean(p.host)}"
        return when (p.purpose) {
            "sudo" -> "sudo — $who"
            "polkit" -> "System authentication — $who"
            else -> "Unlock PC — $who"
        }
    }

    fun promptDetail(p: Parts): String? = if (p.purpose == "sudo") clean(p.command) else null

    /** First 20 hex chars of SHA-256(SPKI DER), uppercase, in groups of four: `AB12 CD34 EF56 7890 1A2B`. */
    fun checkCode(spki: ByteArray): String {
        val hex = MessageDigest.getInstance("SHA-256").digest(spki)
            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
            .substring(0, 20)
        return hex.chunked(4).joinToString(" ")
    }

    /** Drops Unicode control (Cc) and format (Cf, e.g. bidi overrides) characters from text shown to the user. */
    private fun clean(s: String): String = s.filter {
        val t = Character.getType(it)
        t != Character.CONTROL.toInt() && t != Character.FORMAT.toInt()
    }
}
