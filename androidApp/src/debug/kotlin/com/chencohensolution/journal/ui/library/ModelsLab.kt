package com.chencohensolution.journal.ui.library

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.chencohensolution.journal.local.ModelsOnboardingState
import com.chencohensolution.journal.local.PhoneModelKind
import com.chencohensolution.journal.local.PhoneModelRow
import com.chencohensolution.journal.local.PhoneModelStatus
import com.chencohensolution.journal.local.PhoneModelsState

/** Made-up states of Settings › Models on this phone (chosen from option A, docs/screens/mockups/models/). */
internal object ModelsLab {
    private const val MB = 1_000_000L
    private val photos = PhoneModelRow(PhoneModelKind.PHOTOS, PhoneModelStatus.HERE, 10_900_000)
    private val words = PhoneModelRow(PhoneModelKind.WORDS, PhoneModelStatus.MISSING, 290 * MB, withBytes = 440 * MB)

    val usual = PhoneModelsState(listOf(photos, words), freeBytes = 48_000 * MB, wifiOnly = true)
    val downloading = usual.copy(
        models = listOf(photos, words.copy(status = PhoneModelStatus.DOWNLOADING, downloadedBytes = 112 * MB, downloadingBytes = 290 * MB)),
    )
    val waiting = usual.copy(
        models = listOf(photos.copy(status = PhoneModelStatus.WAITING_FOR_WIFI), words.copy(status = PhoneModelStatus.WAITING_FOR_WIFI)),
    )
    val offline = usual.copy(
        models = listOf(photos.copy(status = PhoneModelStatus.NOT_OFFERED, bytes = 0), words.copy(status = PhoneModelStatus.FAILED)),
        offline = true,
    )

    /** The onboarding step a moment after it showed: the photo model downloading, Search by words waiting for Wi-Fi. */
    val onboarding = ModelsOnboardingState(
        show = true,
        models = usual.copy(
            models = listOf(
                photos.copy(status = PhoneModelStatus.DOWNLOADING, downloadedBytes = 4_200_000, downloadingBytes = 10_900_000),
                words.copy(status = PhoneModelStatus.WAITING_FOR_WIFI),
            ),
        ),
    )
}

/** [content] in Hebrew, right to left, as on a phone set to Hebrew. */
@Composable
internal fun LabHebrew(content: @Composable () -> Unit) {
    val configuration = Configuration(LocalConfiguration.current).apply { setLocale(java.util.Locale.forLanguageTag("iw")) }
    val hebrew = LocalContext.current.createConfigurationContext(configuration)
    CompositionLocalProvider(
        LocalContext provides hebrew,
        LocalConfiguration provides configuration,
        LocalLayoutDirection provides LayoutDirection.Rtl,
    ) { content() }
}
