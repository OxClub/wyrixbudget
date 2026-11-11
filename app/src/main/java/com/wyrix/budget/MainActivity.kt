package com.wyrix.budget

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wyrix.budget.core.money.CurrencyInfo
import com.wyrix.budget.data.prefs.AppSettings
import com.wyrix.budget.data.prefs.DemoMode
import com.wyrix.budget.data.prefs.UserPreferencesRepository
import com.wyrix.budget.data.security.AppLockManager
import com.wyrix.budget.data.security.BiometricHelper
import com.wyrix.budget.data.security.LockState
import com.wyrix.budget.data.security.PinManager
import com.wyrix.budget.presentation.components.LocalBaseCurrency
import com.wyrix.budget.presentation.components.LocalHideAmounts
import com.wyrix.budget.presentation.lock.AppRestarter
import com.wyrix.budget.presentation.lock.LockScreen
import com.wyrix.budget.presentation.navigation.WyrixApp
import com.wyrix.budget.presentation.onboarding.OnboardingScreen
import com.wyrix.budget.presentation.theme.WyrixTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val prefs: UserPreferencesRepository,
    private val pin: PinManager,
    private val lock: AppLockManager,
) : ViewModel() {
    /** Null until settings are first read, so onboarding never flashes for returning users. */
    val settings: StateFlow<AppSettings?> = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val lockState: StateFlow<LockState> = lock.state

    init { viewModelScope.launch { lock.initialize(pin.isPinSet.first()) } }

    fun onBackground() = lock.markBackgrounded()
    fun onForeground() {
        viewModelScope.launch { lock.evaluateOnForeground(pin.isPinSet.first(), prefs.settings.first().autoLockSeconds) }
    }
    fun unlock() = lock.unlock()
}

// FragmentActivity is required by BiometricPrompt; it is still a ComponentActivity, so Compose works as usual.
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val lockState by viewModel.lockState.collectAsStateWithLifecycle()
            val s = settings
            LaunchedEffect(s?.blockScreenshots) {
                if (s?.blockScreenshots == true) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            WyrixTheme(themeMode = s?.themeMode ?: com.wyrix.budget.domain.model.ThemeMode.SYSTEM) {
                CompositionLocalProvider(
                    LocalBaseCurrency provides CurrencyInfo.byCode(s?.baseCurrency ?: "INR"),
                    LocalHideAmounts provides (s?.hideAmounts ?: false),
                ) {
                    when {
                        s == null || lockState == LockState.UNKNOWN -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                        lockState == LockState.LOCKED -> LockScreen(onBiometric = {
                            BiometricHelper.prompt(this@MainActivity, "Unlock Budget", onSuccess = viewModel::unlock)
                        })
                        !s.onboardingDone -> OnboardingScreen()
                        else -> WyrixApp(
                            demo = DemoMode.isOn(this@MainActivity),
                            onExitDemo = { DemoMode.set(this@MainActivity, false); deleteDatabase("wyrix_demo.db"); AppRestarter.restart(this@MainActivity) },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); viewModel.onForeground() }
    override fun onStop() { viewModel.onBackground(); super.onStop() }
}
