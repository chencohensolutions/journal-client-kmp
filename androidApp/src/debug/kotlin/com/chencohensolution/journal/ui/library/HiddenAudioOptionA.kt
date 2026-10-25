package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.server.HiddenCollections
import java.text.NumberFormat

// Hidden collections with sound recordings (option a): a copy of HiddenContent's ready state with a "Sound
// recordings" card under Nudity: recordings, phone calls and WhatsApp voice messages, each with its count, all
// three on (hidden) until the user turns one off. The nudity slider and app search of the real screen are left out of
// this copy; they stay as they are.

/** What this copy of the screen can ask for. */
internal class HiddenAudioActions(
    val back: () -> Unit = {},
    val setRecordings: (Boolean) -> Unit = {},
    val setCalls: (Boolean) -> Unit = {},
    val setVoiceMessages: (Boolean) -> Unit = {},
    val set: (HiddenCollections.Collection, Boolean) -> Unit = { _, _ -> },
    val setNudityHidden: (Boolean) -> Unit = {},
)

@Composable
internal fun HiddenAudioScreenA(collections: HiddenCollections, audio: HiddenAudio, actions: HiddenAudioActions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    val format = NumberFormat.getIntegerInstance()
    val divider = LibraryColors.Muted.copy(alpha = 0.2f)
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            Text(stringResource(R.string.hidden_intro), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
            SectionTitle(stringResource(R.string.hidden_nudity_title))
            SettingsCard {
                SwitchRow(
                    stringResource(R.string.hidden_nudity),
                    pluralStringResource(R.plurals.hidden_files, collections.nudity.files, format.format(collections.nudity.files)),
                    collections.nudity.hidden,
                    actions.setNudityHidden,
                )
            }
            SectionTitle(stringResource(R.string.hidden_audio_title))
            SettingsCard {
                SwitchRow(
                    stringResource(R.string.hidden_audio_recordings),
                    pluralStringResource(R.plurals.hidden_audio_recordings_count, audio.recordings, format.format(audio.recordings)),
                    audio.hideRecordings,
                    actions.setRecordings,
                )
                HorizontalDivider(color = divider)
                SwitchRow(
                    stringResource(R.string.hidden_audio_calls),
                    pluralStringResource(R.plurals.hidden_audio_calls_count, audio.calls, format.format(audio.calls)),
                    audio.hideCalls,
                    actions.setCalls,
                )
                HorizontalDivider(color = divider)
                SwitchRow(
                    stringResource(R.string.hidden_audio_voice),
                    pluralStringResource(R.plurals.hidden_audio_voice_count, audio.voiceMessages, format.format(audio.voiceMessages)),
                    audio.hideVoiceMessages,
                    actions.setVoiceMessages,
                )
            }
            Text(stringResource(R.string.hidden_audio_body), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
            SectionTitle(stringResource(R.string.hidden_whatsapp_title))
            SettingsCard {
                SwitchRow(
                    stringResource(R.string.hidden_whatsapp),
                    pluralStringResource(R.plurals.hidden_files, collections.whatsapp.files, format.format(collections.whatsapp.files)),
                    collections.whatsapp.hidden,
                ) { actions.set(collections.whatsapp, it) }
            }
            SectionTitle(stringResource(R.string.hidden_screenshots_title))
            SettingsCard {
                SwitchRow(
                    stringResource(R.string.hidden_without_app),
                    pluralStringResource(R.plurals.hidden_screenshots, collections.screenshotsWithoutApp.files, format.format(collections.screenshotsWithoutApp.files)),
                    collections.screenshotsWithoutApp.hidden,
                ) { actions.set(collections.screenshotsWithoutApp, it) }
                collections.screenshotApps.forEach { app ->
                    HorizontalDivider(color = divider)
                    SwitchRow(app.name, pluralStringResource(R.plurals.hidden_screenshots, app.files, format.format(app.files)), app.hidden) { actions.set(app, it) }
                }
            }
        }
        TitleBar(stringResource(R.string.settings_hidden_title), actions.back, Modifier.align(Alignment.TopCenter))
    }
}
