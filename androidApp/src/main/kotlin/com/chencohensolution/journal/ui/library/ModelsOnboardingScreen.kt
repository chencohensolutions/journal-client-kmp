package com.chencohensolution.journal.ui.library

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.local.ModelsOnboardingState
import com.chencohensolution.journal.local.PhoneModelKind
import com.chencohensolution.journal.local.PhoneModelRow
import com.chencohensolution.journal.local.PhoneModelStatus

internal class ModelsOnboardingActions(
    val setSearchByWords: (Boolean) -> Unit = {},
    val setWifiOnly: (Boolean) -> Unit = {},
    val downloadNow: (PhoneModelKind) -> Unit = {},
    val continueOn: () -> Unit = {},
)

/**
 * The onboarding step after photo access (Nov 8, 2026; docs/screens/enrollment.md "Models on this phone"): the models
 * the server offers, each with its size and a bar. Photo similarity downloads at once; Search by words is an option,
 * checked at first, that waits for Wi-Fi when Only on Wi-Fi is on. Continue never waits. Follows the system theme, as
 * photo access does.
 */
@Composable
internal fun ModelsOnboardingScreen(state: ModelsOnboardingState, actions: ModelsOnboardingActions) {
    val models = state.models
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.systemBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.models_onboarding_title), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.models_onboarding_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                if (!models.known) {
                    Text(
                        stringResource(R.string.models_onboarding_checking),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    models.models.filter { it.status != PhoneModelStatus.NOT_OFFERED }.forEach { model ->
                        ModelCard(model, if (model.kind == PhoneModelKind.WORDS) state.searchByWords else null, actions)
                    }
                    if (models.models.any { it.kind == PhoneModelKind.WORDS && it.status != PhoneModelStatus.NOT_OFFERED }) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(models.wifiOnly, role = Role.Switch, onValueChange = actions.setWifiOnly)
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(stringResource(R.string.models_wifi_only), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(R.string.models_onboarding_wifi_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = models.wifiOnly, onCheckedChange = null)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.models_onboarding_later),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Button(onClick = actions.continueOn, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text(stringResource(R.string.models_onboarding_continue))
            }
        }
    }
}

@Composable
private fun size(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** A model: its name, what it does and its size, how its download stands with a bar; Search by words with its check box. */
@Composable
private fun ModelCard(model: PhoneModelRow, checked: Boolean?, actions: ModelsOnboardingActions) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (model.kind == PhoneModelKind.PHOTOS) R.string.models_photos_title else R.string.models_words_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(if (model.kind == PhoneModelKind.PHOTOS) R.string.models_photos_body else R.string.models_words_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (model.kind == PhoneModelKind.WORDS && model.withBytes > 0) {
                            stringResource(R.string.models_onboarding_words_size, size(model.bytes), size(model.withBytes))
                        } else if (model.bytes > 0) {
                            size(model.bytes)
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (checked != null) Checkbox(checked = checked, onCheckedChange = actions.setSearchByWords)
            }
            if (checked != false) Progress(model, actions)
        }
    }
}

@Composable
private fun Progress(model: PhoneModelRow, actions: ModelsOnboardingActions) {
    val line = when (model.status) {
        PhoneModelStatus.HERE -> stringResource(R.string.models_onboarding_ready)
        PhoneModelStatus.DOWNLOADING -> stringResource(R.string.models_downloading, size(model.downloadedBytes), size(model.downloadingBytes))
        PhoneModelStatus.WAITING_FOR_WIFI -> stringResource(R.string.models_waiting)
        PhoneModelStatus.FAILED -> stringResource(R.string.models_failed)
        PhoneModelStatus.MISSING, PhoneModelStatus.NOT_OFFERED -> stringResource(R.string.models_onboarding_starting)
    }
    val progress = when (model.status) {
        PhoneModelStatus.HERE -> 1f
        PhoneModelStatus.DOWNLOADING -> if (model.downloadingBytes > 0) (model.downloadedBytes.toFloat() / model.downloadingBytes).coerceIn(0f, 1f) else 0f
        else -> 0f
    }
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = if (model.status == PhoneModelStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (model.status == PhoneModelStatus.WAITING_FOR_WIFI || model.status == PhoneModelStatus.FAILED) {
            androidx.compose.material3.TextButton(onClick = { actions.downloadNow(model.kind) }) {
                Text(stringResource(if (model.status == PhoneModelStatus.FAILED) R.string.models_try_again else R.string.models_download_now))
            }
        }
    }
}
