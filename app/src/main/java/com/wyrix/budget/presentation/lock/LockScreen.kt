package com.wyrix.budget.presentation.lock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wyrix.budget.data.prefs.UserPreferencesRepository
import com.wyrix.budget.data.security.AppLockManager
import com.wyrix.budget.data.security.PinManager
import com.wyrix.budget.presentation.components.UiViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LockViewModel @Inject constructor(private val pin: PinManager, private val lock: AppLockManager, prefs: UserPreferencesRepository) : UiViewModel() {
    val biometricEnabled: StateFlow<Boolean> = prefs.settings.map { it.biometricEnabled }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val lockedOutUntil = pin.lockedOutUntil
    private val _entered = MutableStateFlow("")
    val entered: StateFlow<String> = _entered
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun digit(d: Char) {
        if (_entered.value.length >= 8) return
        _entered.update { it + d }
        _error.value = null
    }

    fun backspace() { _entered.update { it.dropLast(1) } }

    fun submit() {
        val attempt = _entered.value
        if (attempt.length < 4) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (now < pin.lockedOutUntil.value) { _error.value = "Too many attempts. Try again in ${(pin.lockedOutUntil.value - now) / 1000 + 1}s."; _entered.value = ""; return@launch }
            if (pin.verify(attempt)) unlock() else {
                _error.value = if (now < pin.lockedOutUntil.value) "Too many attempts. Wait ${(pin.lockedOutUntil.value - now) / 1000 + 1}s." else "Wrong PIN"
                _entered.value = ""
            }
        }
    }

    fun unlock() { _entered.value = ""; lock.unlock() }
}

@Composable
fun LockScreen(onBiometric: () -> Unit, vm: LockViewModel = hiltViewModel()) {
    val entered by vm.entered.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val biometric by vm.biometricEnabled.collectAsStateWithLifecycle()
    LaunchedEffect(biometric) { if (biometric) onBiometric() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Budget is locked", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("Enter your PIN", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Text("•".repeat(entered.length).ifEmpty { " " }, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { contentDescription = "${entered.length} digits entered" })
            Text(error ?: " ", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "bio", "0", "del")
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    row.forEach { k ->
                        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                            when (k) {
                                "bio" -> if (biometric) IconButton(onClick = onBiometric) { Icon(Icons.Filled.Fingerprint, "Use fingerprint") }
                                "del" -> IconButton(onClick = vm::backspace) { Icon(Icons.Filled.Backspace, "Delete last digit") }
                                else -> Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(64.dp).clickable { vm.digit(k[0]) }.semantics { contentDescription = "Digit $k" }) {
                                    Box(contentAlignment = Alignment.Center) { Text(k, style = MaterialTheme.typography.titleLarge) }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = vm::submit, enabled = entered.length >= 4) { Text("Unlock") }
        }
    }
}
