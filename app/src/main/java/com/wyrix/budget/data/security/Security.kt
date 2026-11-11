package com.wyrix.budget.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.wyrix.budget.data.prefs.PrefKeys
import com.wyrix.budget.data.prefs.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** AES-256-GCM with a non-exportable key held in the Android Keystore (hardware-backed where available). */
object KeystoreCrypto {
    private const val ALIAS = "wyrix_local_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Output layout: [ivLength:1][iv][ciphertext+tag]. */
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }
}

/**
 * Stores a salted PBKDF2 hash of the PIN, itself encrypted with the Keystore key. The PIN is never
 * stored in plain text. After repeated wrong attempts entry is temporarily blocked.
 */
@Singleton
class PinManager @Inject constructor(private val prefs: UserPreferencesRepository) {
    val isPinSet: Flow<Boolean> = prefs.pinBlob.map { it != null }

    private val _lockedOutUntil = MutableStateFlow(0L)
    val lockedOutUntil: StateFlow<Long> = _lockedOutUntil
    private var failures = 0

    suspend fun setPin(pin: String) {
        require(pin.length in 4..8 && pin.all { it.isDigit() }) { "PIN must be 4 to 8 digits" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val blob = KeystoreCrypto.encrypt(salt + hash(pin, salt))
        prefs.set(PrefKeys.PIN_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP))
        failures = 0
        _lockedOutUntil.value = 0
    }

    suspend fun verify(pin: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (nowMillis < _lockedOutUntil.value) return false
        val stored = prefs.pinBlob.first() ?: return true
        val plain = try { KeystoreCrypto.decrypt(Base64.decode(stored, Base64.NO_WRAP)) } catch (e: Exception) { return false }
        val salt = plain.copyOfRange(0, 16)
        val expected = plain.copyOfRange(16, plain.size)
        val ok = MessageDigest.isEqual(expected, hash(pin, salt))
        if (ok) {
            failures = 0
        } else {
            failures++
            if (failures >= 5) _lockedOutUntil.value = nowMillis + 30_000L * (failures - 4)
        }
        return ok
    }

    suspend fun clear() {
        prefs.remove(PrefKeys.PIN_BLOB)
        prefs.set(PrefKeys.BIOMETRIC_ENABLED, false)
        failures = 0
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)).encoded
}

enum class LockState { UNKNOWN, LOCKED, UNLOCKED }

/** Decides when the app is locked: at start, and after being in the background longer than the timeout. */
@Singleton
class AppLockManager @Inject constructor() {
    private val _state = MutableStateFlow(LockState.UNKNOWN)
    val state: StateFlow<LockState> = _state
    private var backgroundedAt = 0L
    private var skipNext = false

    fun initialize(pinSet: Boolean) {
        if (_state.value == LockState.UNKNOWN) _state.value = if (pinSet) LockState.LOCKED else LockState.UNLOCKED
    }

    fun markBackgrounded(now: Long = System.currentTimeMillis()) { backgroundedAt = now }

    /** Call before launching a system camera/picker so returning from it doesn't trigger a lock. */
    fun beginExternalFlow() { skipNext = true }

    fun evaluateOnForeground(pinSet: Boolean, timeoutSeconds: Int, now: Long = System.currentTimeMillis()) {
        if (skipNext) { skipNext = false; return }
        if (!pinSet) { _state.value = LockState.UNLOCKED; return }
        if (_state.value == LockState.UNLOCKED && backgroundedAt > 0 && now - backgroundedAt >= timeoutSeconds * 1000L) {
            _state.value = LockState.LOCKED
        }
    }

    fun unlock() { _state.value = LockState.UNLOCKED }
    fun lockNow() { _state.value = LockState.LOCKED }
}

object BiometricHelper {
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, title: String, onSuccess: () -> Unit, onFailure: (String) -> Unit = {}) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure(errString.toString())
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info)
    }
}
