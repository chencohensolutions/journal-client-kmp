package com.chencohensolution.journal.ui.library

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohensolution.journal.R
import com.chencohensolution.journal.settings.ScreenLock

/**
 * Shows [content] only after the phone's own lock is passed (fingerprint, face, PIN, pattern or password: whatever the
 * phone uses), every time the screen opens and again after the app was in the background. Being unlocked is never
 * saved, so a screen brought back after the app was closed asks again. A phone with no screen lock can't open it.
 */
@Composable
internal fun PhoneLocked(
    title: String,
    back: () -> Unit,
    lockedText: Int = R.string.phone_lock_locked,
    noLockText: Int = R.string.phone_lock_none,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val lock = remember { ScreenLock() }
    val unlocked by lock.state.collectAsStateWithLifecycle()
    var attempt by remember { mutableIntStateOf(0) }
    val secure = remember { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { lock.wentToBackground() }

    if (unlocked) {
        content()
        return
    }
    if (secure) {
        DisposableEffect(attempt) {
            val cancel = askForPhoneLock(context, title, lock::unlocked)
            onDispose { cancel.cancel() }
        }
    }
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(LibraryIcons.Lock, contentDescription = null, tint = LibraryColors.Muted, modifier = Modifier.size(40.dp))
            Text(
                stringResource(if (secure) lockedText else noLockText),
                color = LibraryColors.OnBackground,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
            if (secure) {
                Button(
                    onClick = { attempt++ },
                    colors = ButtonDefaults.buttonColors(containerColor = LibraryColors.Inverse, contentColor = LibraryColors.OnInverse),
                ) { Text(stringResource(R.string.phone_lock_unlock)) }
            }
        }
        TitleBar(title, back, Modifier.align(Alignment.TopCenter))
    }
}

/** Shows the system's lock prompt; [onUnlocked] runs once it is passed. Cancelled or failed, the screen stays locked. */
private fun askForPhoneLock(context: Context, title: String, onUnlocked: () -> Unit): CancellationSignal {
    val cancel = CancellationSignal()
    BiometricPrompt.Builder(context)
        .setTitle(title)
        .setSubtitle(context.getString(R.string.phone_lock_prompt))
        .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
        .build()
        .authenticate(cancel, context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()
        })
    return cancel
}

