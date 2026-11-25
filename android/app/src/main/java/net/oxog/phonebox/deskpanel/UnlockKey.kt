package net.oxog.phonebox.deskpanel

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The phone's unlock key: an EC P-256 key pair created inside the Android Keystore (secure hardware where the phone
 * has it). The private key never leaves it. The Keystore signs with it only within [WINDOW_SECONDS] after a strong
 * biometric: the phone's own fingerprint unlock, the touch-to-unlock sensor or the fingerprint prompt ([prompt]). That
 * window is what lets one press of the power button unlock the phone and the PC. Enrolling a new fingerprint
 * invalidates the key for good; the panel then offers setup again, as it does for a key from before the window
 * (per use, [UnlockKeyRotation.State.OUTDATED]). Creating the key needs no fingerprint; a re-setup keeps the working
 * key until the agent accepted the new one ([replace]).
 */
@RequiresApi(28)
class UnlockKey(private val activity: Activity) : UnlockKeyRotation.Slots {

    private fun store(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }

    private val prefs by lazy { activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun activeAlias(): String = prefs.getString(PREF_ALIAS, null) ?: UnlockKeyRotation.LEGACY

    /** Written synchronously: the old key is deleted right after, so the pointer must already be on disk. */
    override fun setActiveAlias(alias: String) {
        check(prefs.edit().putString(PREF_ALIAS, alias).commit()) { "could not save the active unlock key" }
    }

    /** Creates (or replaces) the key pair under [alias] and returns its public key as X.509 SubjectPublicKeyInfo (DER). */
    override fun create(alias: String): ByteArray {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(WINDOW_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG)
                // Android 9-10 cannot limit the window to biometrics: a PIN unlock opens it there too
                else @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(WINDOW_SECONDS)
            }
            .build()
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE)
        gen.initialize(spec)
        return gen.generateKeyPair().public.encoded
    }

    override fun delete(alias: String) {
        try { store().deleteEntry(alias) } catch (e: Exception) { Log.w(TAG, "could not delete an unlock key", e) }
    }

    /**
     * Makes a new key and hands its public key to [enroll]; the new key replaces the working one only when [enroll]
     * returns true (see [UnlockKeyRotation.rotate]). Returns the new public key (SPKI DER), or null.
     */
    fun replace(enroll: (ByteArray) -> Boolean): ByteArray? = UnlockKeyRotation.rotate(this, enroll)

    /** Check code of the active key for the panel: "" only when it is confirmed missing, invalidated or outdated. */
    fun code(): String {
        val state = probe()
        val spki = if (state == UnlockKeyRotation.State.OK) {
            try { store().getCertificate(activeAlias())?.publicKey?.encoded } catch (e: Exception) { null }
        } else null
        return UnlockKeyRotation.code(state, spki)
    }

    /**
     * The state of the active key. A key invalidated by a new fingerprint is deleted here; a key without the window
     * is OUTDATED (left alone, a re-setup replaces it); any other failure is ERROR. "Not authenticated just now" is OK.
     */
    private fun probe(): UnlockKeyRotation.State {
        val alias = activeAlias()
        val key = try {
            val ks = store()
            if (!ks.containsAlias(alias)) return UnlockKeyRotation.State.MISSING
            ks.getKey(alias, null) as? PrivateKey
        } catch (e: KeyPermanentlyInvalidatedException) {
            return invalidated(alias)
        } catch (e: Exception) {
            Log.w(TAG, "unlock key unreadable", e)
            return UnlockKeyRotation.State.ERROR
        } ?: return UnlockKeyRotation.State.MISSING
        return try {
            val info = KeyFactory.getInstance(key.algorithm, STORE).getKeySpec(key, KeyInfo::class.java)
            @Suppress("DEPRECATION")
            if (info.userAuthenticationValidityDurationSeconds <= 0) return UnlockKeyRotation.State.OUTDATED
            try {
                Signature.getInstance(ALGORITHM).initSign(key)
            } catch (_: UserNotAuthenticatedException) {
                // fine: the key is there, it just waits for a fingerprint
            }
            UnlockKeyRotation.State.OK
        } catch (e: KeyPermanentlyInvalidatedException) {
            invalidated(alias)
        } catch (e: Exception) {
            Log.w(TAG, "unlock key unusable", e)
            UnlockKeyRotation.State.ERROR
        }
    }

    private fun invalidated(alias: String): UnlockKeyRotation.State {
        Log.i(TAG, "unlock key invalidated by a biometric change; deleting it")
        delete(alias)
        return UnlockKeyRotation.State.INVALIDATED
    }

    /**
     * Signs [data] with the active key, or null when no strong biometric opened it within the last [WINDOW_SECONDS]
     * (or the key is missing or unusable). Any thread.
     */
    fun signNow(data: ByteArray): ByteArray? = try {
        val key = store().getKey(activeAlias(), null) as? PrivateKey
        key?.let { Signature.getInstance(ALGORITHM).apply { initSign(it); update(data) }.sign() }
    } catch (e: UserNotAuthenticatedException) {
        null
    } catch (e: Exception) {
        Log.w(TAG, "signing failed: ${e.javaClass.simpleName}")
        null
    }

    /**
     * Shows the fingerprint prompt and signs [challenge] (its UTF-8 bytes) with the key. [onDone] runs once, on the
     * UI thread: the base64 DER signature, or null if the user cancelled, the prompt failed or the key is unusable.
     * Call on the UI thread.
     */
    fun sign(challenge: String, title: String, detail: String?, onDone: (String?) -> Unit) =
        prompt(title, detail) { ok ->
            onDone(if (ok) signNow(challenge.toByteArray(Charsets.UTF_8))?.let { Base64.encodeToString(it, Base64.NO_WRAP) } else null)
        }

    /**
     * Shows the fingerprint prompt (strong biometrics only). [onDone] runs once, on the UI thread: true when a
     * fingerprint passed, which opens the key for [WINDOW_SECONDS]; false if the user cancelled or the prompt failed.
     */
    fun prompt(title: String, detail: String?, onDone: (Boolean) -> Unit) {
        var done = false
        val finish = { ok: Boolean -> if (!done) { done = true; onDone(ok) } }
        val executor = activity.mainExecutor
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .apply { if (detail != null) setDescription(detail) } // the description wraps; a subtitle would cut a long command
            .setNegativeButton("Cancel", executor) { _, _ -> finish(false) }
            .apply { if (Build.VERSION.SDK_INT >= 30) setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG) }
            .build()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { finish(true) }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { finish(false) }
            // onAuthenticationFailed: a finger that did not match; the prompt stays open for another try.
        }
        try {
            prompt.authenticate(CancellationSignal(), executor, callback)
        } catch (e: Exception) {
            Log.w(TAG, "fingerprint prompt failed", e)
            finish(false)
        }
    }

    /**
     * The sensor for touch-to-unlock ([UnlockListener]): the legacy FingerprintManager, which listens without any
     * system dialog. A touch opens the key for [WINDOW_SECONDS]. [miss] runs for a finger that did not match (the
     * session goes on).
     */
    @Suppress("DEPRECATION")
    fun fingerprintSensor(miss: () -> Unit): UnlockListener.Sensor<Unit> = object : UnlockListener.Sensor<Unit> {
        private fun fm(): FingerprintManager? = try { activity.getSystemService(FingerprintManager::class.java) } catch (_: Exception) { null }

        override fun available(): Boolean = try {
            fm()?.let { it.isHardwareDetected && it.hasEnrolledFingerprints() } == true &&
                probe() == UnlockKeyRotation.State.OK
        } catch (e: Exception) { false }

        override fun start(onTouch: (Unit) -> Unit, onMiss: () -> Unit, onError: (retryMs: Long?) -> Unit): (() -> Unit)? {
            val m = fm() ?: return null
            val signal = CancellationSignal()
            val callback = object : FingerprintManager.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: FingerprintManager.AuthenticationResult) { onTouch(Unit) }
                override fun onAuthenticationFailed() { miss(); onMiss() }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    Log.i(TAG, "touch unlock: sensor stopped ($errorCode)")
                    // cancelled by the system or the hardware busy for a moment: try again; Android's timed lockout: try
                    // again once it is over; anything else stops
                    onError(when (errorCode) {
                        FingerprintManager.FINGERPRINT_ERROR_CANCELED, FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE -> 0L
                        FingerprintManager.FINGERPRINT_ERROR_LOCKOUT -> UnlockListener.LOCKOUT_RETRY_MS
                        else -> null
                    })
                }
            }
            return try {
                m.authenticate(null, signal, 0, callback, Handler(Looper.getMainLooper()))
                ({ signal.cancel() })
            } catch (e: Exception) {
                Log.w(TAG, "touch unlock: could not listen", e)
                null
            }
        }
    }

    companion object {
        /** How long a strong biometric opens the key. */
        const val WINDOW_SECONDS = 5
        private const val STORE = "AndroidKeyStore"
        private const val ALGORITHM = "SHA256withECDSA"
        private const val PREFS = "unlock-key"
        private const val PREF_ALIAS = "alias"
        private const val TAG = "UnlockKey"
    }
}
