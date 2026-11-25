package net.oxog.phonebox.deskpanel

/**
 * Re-setup without losing the working key: the Keystore cannot rename an entry, so the unlock key lives under one of
 * two aliases and a preference names the active one. A new key is made under the spare alias and becomes active only
 * after the agent accepted it (HTTP 200); until then the old key keeps answering sudo/polkit.
 */
object UnlockKeyRotation {
    /** Where keys made before rotation existed live; read as the active alias until the first re-setup. */
    const val LEGACY = "phonebox-unlock"
    const val A = "phonebox-unlock-a"
    const val B = "phonebox-unlock-b"

    /** What [code] returns when the key could not be read just now: not "", so the panel does not offer setup. */
    const val UNKNOWN = "?"

    /** OUTDATED: a key from before the 5 s window (per use); the panel offers setup to replace it. */
    enum class State { OK, MISSING, INVALIDATED, OUTDATED, ERROR }

    interface Slots {
        fun activeAlias(): String
        fun setActiveAlias(alias: String)
        /** Creates (or replaces) the key under [alias]; returns its public key (SPKI DER). */
        fun create(alias: String): ByteArray
        fun delete(alias: String)
    }

    fun spare(active: String): String = if (active == A) B else A

    /**
     * Makes a new key under the spare alias and offers it to [enroll]. On true the new key becomes active and the old
     * one is deleted; otherwise (false, an exception, creation or saving the pointer failing) the new key is dropped
     * and the old one stays. Returns the new public key, or null.
     */
    fun rotate(slots: Slots, enroll: (ByteArray) -> Boolean): ByteArray? {
        val old = slots.activeAlias()
        val next = spare(old)
        val spki = try {
            slots.delete(next) // a leftover from an interrupted setup
            slots.create(next)
        } catch (e: Exception) {
            slots.delete(next)
            return null
        }
        val accepted = try { enroll(spki) } catch (e: Exception) { false }
        if (!accepted) {
            slots.delete(next)
            return null
        }
        try {
            slots.setActiveAlias(next)
        } catch (e: Exception) {
            slots.delete(next)
            return null
        }
        slots.delete(old)
        return spki
    }

    /** The check code for the panel: "" only when the key is confirmed gone; [UNKNOWN] on a transient error. */
    fun code(state: State, spki: ByteArray?): String = when (state) {
        State.MISSING, State.INVALIDATED, State.OUTDATED -> ""
        State.ERROR -> UNKNOWN
        State.OK -> spki?.let { UnlockChallenge.checkCode(it) } ?: UNKNOWN
    }
}
