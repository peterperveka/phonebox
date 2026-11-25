package net.oxog.phonebox.deskpanel

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The phone's unlock key: an EC P-256 key pair created inside the Android Keystore (secure hardware where the phone
 * has it). The private key never leaves it, and because it requires user authentication for every single use, the
 * Keystore only signs with it inside a successful fingerprint prompt ([sign]). Enrolling a new fingerprint
 * invalidates the key for good; the panel then offers setup again. Creating the key needs no fingerprint; a re-setup
 * keeps the working key until the agent accepted the new one ([replace]).
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
                if (Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                // -1: authentication is needed for every use (the API 28-29 spelling of "per use")
                else @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(-1)
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

    /** Check code of the active key for the panel: "" only when it is confirmed missing or invalidated. */
    fun code(): String {
        val (state, _) = probe()
        val spki = if (state == UnlockKeyRotation.State.OK) {
            try { store().getCertificate(activeAlias())?.publicKey?.encoded } catch (e: Exception) { null }
        } else null
        return UnlockKeyRotation.code(state, spki)
    }

    /**
     * The state of the active key, with a Signature ready for the prompt when it is usable. A key invalidated by a
     * new fingerprint is deleted here; any other failure is ERROR (the key is left alone). Initialising needs no
     * authentication; signing does.
     */
    private fun probe(): Pair<UnlockKeyRotation.State, Signature?> {
        val alias = activeAlias()
        val key = try {
            val ks = store()
            if (!ks.containsAlias(alias)) return UnlockKeyRotation.State.MISSING to null
            ks.getKey(alias, null) as? PrivateKey
        } catch (e: KeyPermanentlyInvalidatedException) {
            return invalidated(alias)
        } catch (e: Exception) {
            Log.w(TAG, "unlock key unreadable", e)
            return UnlockKeyRotation.State.ERROR to null
        } ?: return UnlockKeyRotation.State.MISSING to null
        return try {
            UnlockKeyRotation.State.OK to Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            invalidated(alias)
        } catch (e: Exception) {
            Log.w(TAG, "unlock key unusable", e)
            UnlockKeyRotation.State.ERROR to null
        }
    }

    private fun invalidated(alias: String): Pair<UnlockKeyRotation.State, Signature?> {
        Log.i(TAG, "unlock key invalidated by a biometric change; deleting it")
        delete(alias)
        return UnlockKeyRotation.State.INVALIDATED to null
    }

    private fun signer(): Signature? = probe().second

    /**
     * Shows the fingerprint prompt and signs [challenge] (its UTF-8 bytes) with the key. [onDone] runs once, on the
     * UI thread: the base64 DER signature, or null if the user cancelled, the prompt failed or the key is unusable.
     * Call on the UI thread.
     */
    fun sign(challenge: String, title: String, detail: String?, onDone: (String?) -> Unit) {
        var done = false
        val finish = { result: String? -> if (!done) { done = true; onDone(result) } }
        val signature = signer() ?: return finish(null)
        val executor = activity.mainExecutor
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .apply { if (detail != null) setDescription(detail) } // the description wraps; a subtitle would cut a long command
            .setNegativeButton("Cancel", executor) { _, _ -> finish(null) }
            .apply { if (Build.VERSION.SDK_INT >= 30) setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG) }
            .build()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val s = result.cryptoObject?.signature
                finish(try {
                    s?.update(challenge.toByteArray(Charsets.UTF_8))
                    s?.sign()?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                } catch (e: Exception) {
                    Log.w(TAG, "signing failed: ${e.javaClass.simpleName}")
                    null
                })
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { finish(null) }
            // onAuthenticationFailed: a finger that did not match; the prompt stays open for another try.
        }
        try {
            prompt.authenticate(BiometricPrompt.CryptoObject(signature), CancellationSignal(), executor, callback)
        } catch (e: Exception) {
            Log.w(TAG, "fingerprint prompt failed", e)
            finish(null)
        }
    }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val PREFS = "unlock-key"
        const val PREF_ALIAS = "alias"
        const val TAG = "UnlockKey"
    }
}
