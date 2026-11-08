package com.chencohensolution.journal.ui.library

import android.content.res.Configuration
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import java.util.Locale

// Models the app downloads instead of carrying them (proposed, Nov 8, 2026). The state is shaped as the shared logic
// would publish it: PhoneModel (sharedLogic local/PhoneModel.kt) for the photo model, LocalIndexes for search's.

/** A model the app runs on this phone. */
internal enum class LabModelKind { PHOTOS, WORDS }

internal enum class LabModelStatus { HERE, MISSING, DOWNLOADING, WAITING_FOR_WIFI, NOT_OFFERED, FAILED }

internal data class LabModel(
    val kind: LabModelKind,
    val bytes: Long,
    val status: LabModelStatus,
    /** While downloading. */
    val downloadedBytes: Long = 0,
)

/** Settings' models: each model, Only on Wi-Fi, whether the server can be reached, the space left. */
internal data class LabModelsState(
    val models: List<LabModel>,
    val wifiOnly: Boolean = true,
    /** The server can't be reached: nothing downloads, what's here still works. */
    val offline: Boolean = false,
    val freeBytes: Long = 48_000 * MB,
) {
    val keptBytes: Long get() = models.filter { it.status == LabModelStatus.HERE }.sumOf { it.bytes }
    val missing: List<LabModel> get() = models.filter { it.status == LabModelStatus.MISSING || it.status == LabModelStatus.FAILED }

    companion object {
        const val MB = 1_000_000L
        private val photos = LabModel(LabModelKind.PHOTOS, 10_900_000, LabModelStatus.HERE)
        private val words = LabModel(LabModelKind.WORDS, 290 * MB, LabModelStatus.MISSING)

        /** The photo model downloaded on first use; search's not yet. */
        val usual = LabModelsState(listOf(photos, words))
        val downloading = LabModelsState(listOf(photos, words.copy(status = LabModelStatus.DOWNLOADING, downloadedBytes = 112 * MB)))
        val waiting = LabModelsState(listOf(photos.copy(status = LabModelStatus.MISSING), words.copy(status = LabModelStatus.WAITING_FOR_WIFI)))
        val allHere = LabModelsState(listOf(photos, words.copy(status = LabModelStatus.HERE)))

        /** Offline, and a server that doesn't offer the photo model (not updated yet). */
        val offline = LabModelsState(listOf(photos.copy(status = LabModelStatus.NOT_OFFERED), words.copy(status = LabModelStatus.FAILED)), offline = true)
    }
}

@Composable
internal fun labModelTitle(kind: LabModelKind): String = stringResource(
    when (kind) {
        LabModelKind.PHOTOS -> R.string.models_photos_title
        LabModelKind.WORDS -> R.string.models_words_title
    },
)

@Composable
internal fun labModelBody(kind: LabModelKind): String = stringResource(
    when (kind) {
        LabModelKind.PHOTOS -> R.string.models_photos_body
        LabModelKind.WORDS -> R.string.models_words_body
    },
)

@Composable
internal fun labSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** "Downloaded · 11 MB", "Downloading 112 of 290 MB", "Not downloaded · 290 MB", "Your server doesn't offer it yet"… */
@Composable
internal fun labModelStatus(model: LabModel): String = when (model.status) {
    LabModelStatus.HERE -> stringResource(R.string.models_here, labSize(model.bytes))
    LabModelStatus.MISSING -> stringResource(R.string.models_missing, labSize(model.bytes))
    LabModelStatus.DOWNLOADING -> stringResource(R.string.models_downloading, labSize(model.downloadedBytes), labSize(model.bytes))
    LabModelStatus.WAITING_FOR_WIFI -> stringResource(R.string.models_waiting, labSize(model.bytes))
    LabModelStatus.NOT_OFFERED -> stringResource(R.string.models_not_offered)
    LabModelStatus.FAILED -> stringResource(R.string.models_failed, labSize(model.bytes))
}

/** One model: its name, what it does, how it stands, a bar while downloading, and its button. */
@Composable
internal fun LabModelRow(model: LabModel, offline: Boolean, showBody: Boolean = true) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(labModelTitle(model.kind), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (showBody) Text(labModelBody(model.kind), color = LibraryColors.Muted, fontSize = 13.sp)
                val warning = model.status == LabModelStatus.NOT_OFFERED || model.status == LabModelStatus.FAILED
                Text(
                    labModelStatus(model),
                    color = if (warning) LibraryColors.Warning else LibraryColors.OnBackground,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            val button = when (model.status) {
                LabModelStatus.HERE -> R.string.models_delete
                LabModelStatus.MISSING -> R.string.models_download
                LabModelStatus.WAITING_FOR_WIFI -> R.string.models_download_now
                LabModelStatus.DOWNLOADING -> R.string.models_pause
                LabModelStatus.FAILED -> R.string.models_try_again
                LabModelStatus.NOT_OFFERED -> null
            }
            if (button != null) {
                TextButton(onClick = {}, enabled = !offline || model.status == LabModelStatus.HERE) {
                    Text(stringResource(button), color = if (!offline || model.status == LabModelStatus.HERE) LibraryColors.OnBackground else LibraryColors.Muted)
                }
            }
        }
        if (model.status == LabModelStatus.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { model.downloadedBytes.toFloat() / model.bytes },
                color = LibraryColors.OnBackground,
                trackColor = LibraryColors.Muted.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp),
            )
        }
    }
}

/** "Your server can't be reached. What's downloaded still works." */
@Composable
internal fun LabOfflineLine() {
    Text(
        stringResource(R.string.models_offline),
        color = LibraryColors.Warning,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 8.dp),
    )
}

/** [content] in Hebrew, right to left, as on a phone set to Hebrew. */
@Composable
internal fun LabHebrew(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.forLanguageTag("iw")) }
    val hebrew = context.createConfigurationContext(configuration)
    CompositionLocalProvider(
        LocalContext provides hebrew,
        LocalConfiguration provides configuration,
        LocalLayoutDirection provides LayoutDirection.Rtl,
    ) { content() }
}
