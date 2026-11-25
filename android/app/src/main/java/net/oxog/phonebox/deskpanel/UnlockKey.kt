package net.oxog.phonebox.deskpanel

import android.app.Activity
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
 * invalidates the key for good; the panel then offers setup again.
 */
@RequiresApi(28)
class UnlockKey(private val activity: Activity) {

    private fun store(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }

    fun exists(): Boolean = try { store().containsAlias(ALIAS) } catch (e: Exception) { false }

    /** Creates (or replaces) the key pair and returns its public key as X.509 SubjectPublicKeyInfo (DER). */
    fun create(): ByteArray {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
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

    /** The public key (SPKI DER) of the existing key, or null if there is none. */
    fun publicKey(): ByteArray? = try { store().getCertificate(ALIAS)?.publicKey?.encoded } catch (e: Exception) { null }

    fun remove() {
        try { store().deleteEntry(ALIAS) } catch (e: Exception) { Log.w(TAG, "could not delete the unlock key", e) }
    }

    /**
     * A Signature ready for the prompt, or null when there is no usable key. A key invalidated by a new fingerprint
     * is deleted here. Initialising needs no authentication; signing does.
     */
    private fun signer(): Signature? {
        val key = try { store().getKey(ALIAS, null) as? PrivateKey } catch (e: Exception) { null } ?: return null
        return try {
            Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.i(TAG, "unlock key invalidated by a biometric change; deleting it")
            remove()
            null
        } catch (e: Exception) {
            Log.w(TAG, "unlock key unusable", e)
            null
        }
    }

    /** False (and the dead key deleted) when the key was invalidated by a new fingerprint. */
    fun usable(): Boolean = signer() != null

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
            .apply { if (detail != null) setSubtitle(detail) }
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
        const val ALIAS = "phonebox-unlock"
        const val TAG = "UnlockKey"
    }
}
