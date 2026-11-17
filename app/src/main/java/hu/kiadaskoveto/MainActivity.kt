package hu.kiadaskoveto

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import hu.kiadaskoveto.data.settings.AppSettings
import hu.kiadaskoveto.ui.KiadasRoot
import hu.kiadaskoveto.ui.LocalAppContainer
import hu.kiadaskoveto.ui.theme.KiadasTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Single activity. FragmentActivity is required by BiometricPrompt (optional app lock).
 */
class MainActivity : FragmentActivity() {

    private val container get() = (application as KiadasApp).container
    private val locked = MutableStateFlow(true)
    private val openRequest = MutableStateFlow<String?>(null)
    private val sharedInput = MutableStateFlow<SharedInput?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { locked.value = true }
        })
        setContent {
            val settings by container.settings.settings.collectAsState(initial = null)
            val isLocked by locked.collectAsState()
            val s = settings
            KiadasTheme(themeMode = s?.themeMode ?: hu.kiadaskoveto.ui.theme.ThemeMode.SYSTEM, dynamicColor = s?.dynamicColor ?: false) {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    KiadasRoot(
                        settings = s,
                        locked = s?.appLock == true && isLocked,
                        onUnlockRequest = { authenticate() },
                        openRequest = openRequest,
                        sharedInput = sharedInput,
                    )
                }
            }
        }
    }

    /** Cached app-lock setting (null until DataStore has been read once). */
    private var lockEnabled: Boolean? = null

    override fun onStart() {
        super.onStart()
        if (lockCollector == null) {
            lockCollector = lifecycleScope.launch {
                container.settings.settings.collect { s: AppSettings ->
                    val firstRead = lockEnabled == null
                    lockEnabled = s.appLock
                    if (!s.appLock) locked.value = false
                    else if (firstRead && locked.value && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) authenticate()
                }
            }
        }
    }

    private var lockCollector: Job? = null

    override fun onResume() {
        super.onResume()
        // Prompt when returning to a locked app.
        if (lockEnabled == true && locked.value) authenticate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_OPEN)?.let { openRequest.value = it }
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let {
                sharedInput.value = SharedInput(listOf(it), intent.type ?: "image/*")
            }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let {
                if (it.isNotEmpty()) sharedInput.value = SharedInput(it, intent.type ?: "image/*")
            }
        }
    }

    fun canUseAppLock(): Boolean =
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticate() {
        if (!canUseAppLock()) { locked.value = false; return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { locked.value = false }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("KiadásKövető feloldása")
                .setSubtitle("Az adataid védelme érdekében azonosítsd magad.")
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build()
        )
    }

    companion object {
        const val EXTRA_OPEN = "open"
    }
}
