package com.wyrix.budget.presentation.settings

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.wyrix.budget.data.backup.BackupCodec
import com.wyrix.budget.data.backup.BackupException
import com.wyrix.budget.data.backup.BackupService
import com.wyrix.budget.data.backup.BackupSnapshot
import com.wyrix.budget.data.backup.WrongPasswordException
import com.wyrix.budget.data.prefs.AppSettings
import com.wyrix.budget.data.prefs.DemoMode
import com.wyrix.budget.data.prefs.PrefKeys
import com.wyrix.budget.data.prefs.UserPreferencesRepository
import com.wyrix.budget.data.security.AppLockManager
import com.wyrix.budget.data.security.PinManager
import com.wyrix.budget.presentation.components.UiViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface RestoreState {
    data object Idle : RestoreState
    data object NeedsPassword : RestoreState
    data class Confirm(val snapshot: BackupSnapshot) : RestoreState
    data object Working : RestoreState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferencesRepository,
    private val pin: PinManager,
    private val backup: BackupService,
    private val lock: AppLockManager,
) : UiViewModel() {
    val settings: StateFlow<AppSettings> = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val pinSet: StateFlow<Boolean> = pin.isPinSet.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val demo: Boolean get() = DemoMode.isOn(context)

    private val _restore = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restore: StateFlow<RestoreState> = _restore
    private var pendingBytes: ByteArray? = null

    private val _restart = Channel<Unit>(Channel.BUFFERED)
    val restart = _restart.receiveAsFlow()

    fun <T> set(key: Preferences.Key<T>, value: T) { launchSafely { prefs.set(key, value) } }

    // ---- PIN --------------------------------------------------------------------------------
    fun setPin(newPin: String, currentPin: String?) {
        launchSafely {
            if (pinSet.value && (currentPin == null || !pin.verify(currentPin))) { say("The current PIN is wrong."); return@launchSafely }
            pin.setPin(newPin); say("PIN saved.")
        }
    }

    fun removePin(currentPin: String) {
        launchSafely { if (pin.verify(currentPin)) { pin.clear(); say("PIN removed.") } else say("The PIN is wrong.") }
    }

    fun lockNow() = lock.lockNow()
    fun beginExternalFlow() = lock.beginExternalFlow()

    // ---- backup -----------------------------------------------------------------------------
    fun createBackup(uri: Uri, password: CharArray?) {
        launchSafely("Couldn't create the backup. Nothing was changed.") {
            val bytes = backup.create(password)
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream") }
            say(if (password != null) "Encrypted backup saved. Keep the password safe: it can't be recovered." else "Backup saved. It is not encrypted, so keep the file private.")
        }
    }

    fun openBackup(uri: Uri) {
        launchSafely("Couldn't read that file.") {
            val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: run { say("Couldn't read that file."); return@launchSafely }
            pendingBytes = bytes
            if (BackupCodec.isEncrypted(bytes)) _restore.value = RestoreState.NeedsPassword else decode(null)
        }
    }

    fun submitRestorePassword(password: CharArray) { launchSafely { decode(password) } }

    private suspend fun decode(password: CharArray?) {
        val bytes = pendingBytes ?: return
        try {
            val snapshot = withContext(Dispatchers.Default) { BackupCodec.decode(bytes, password) }
            _restore.value = RestoreState.Confirm(snapshot)
        } catch (e: WrongPasswordException) {
            say("Wrong password, or the file is damaged."); _restore.value = RestoreState.NeedsPassword
        } catch (e: BackupException) {
            say(e.message ?: "That backup can't be used."); cancelRestore()
        }
    }

    fun confirmRestore() {
        val snapshot = (_restore.value as? RestoreState.Confirm)?.snapshot ?: return
        _restore.value = RestoreState.Working
        launchSafely("Restore failed. Your existing data is untouched.") {
            try { backup.restore(snapshot); _restart.send(Unit) } catch (e: Exception) { _restore.value = RestoreState.Idle; throw e }
        }
    }

    fun cancelRestore() { pendingBytes = null; _restore.value = RestoreState.Idle }

    // ---- demo / delete ----------------------------------------------------------------------
    fun setDemo(on: Boolean) {
        launchSafely {
            DemoMode.set(context, on)
            if (!on) context.deleteDatabase("wyrix_demo.db")
            _restart.send(Unit)
        }
    }

    fun deleteEverything() {
        launchSafely("Couldn't delete everything.") { backup.wipeAll(); pin.clear(); _restart.send(Unit) }
    }
}
