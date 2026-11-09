package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.SoundRecording

// A sound recording's page ("filmstrip", chosen in the UI lab on Oct 25, 2026): its bars large, with a mark at each
// photo taken while it ran; the controls; and the photos under them, the one of the moment playing marked. Tapping a
// mark's place or a photo plays from there. A call's page names who it was with, with Call and Edit contact. Always
// dark, like the viewer. See docs/screens/viewer.md.

/** A recording's page: [during] are the photos taken while it ran, oldest first; [place] is where (from them). */
internal class AudioPageState(val recording: SoundRecording, val place: String?, val during: List<MediaItem>, val playback: AudioPlayback?)

internal class AudioPageActions(
    val play: () -> Unit = {},
    val pause: () -> Unit = {},
    val seek: (Long) -> Unit = {},
    val setSpeed: (Float) -> Unit = {},
    val back: () -> Unit = {},
    /** Opens the phone's dialer on the number. */
    val dial: (String) -> Unit = {},
    /** Saves who a call was with (empty is none); the server's answer comes with the next sync. */
    val setCall: (contact: String, number: String) -> Unit = { _, _ -> },
)

@Composable
internal fun AudioPage(state: AudioPageState, actions: AudioPageActions) {
    val recording = state.recording
    val playback = state.playback?.takeIf { it.sha256 == recording.sha256 }
    val position = playback?.positionMillis ?: 0L
    val length = recording.durationMillis.coerceAtLeast(1)
    val photos = state.during
    val nowIndex = photos.indexOfLast { it.takenAtMillis <= recording.startMillis + position }.takeIf { playback != null } ?: -1
    val strip = rememberLazyListState()
    LaunchedEffect(nowIndex) { if (nowIndex >= 0) strip.animateScrollToItem(nowIndex) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val insets = barsRoom()
    Column(
        Modifier.fillMaxSize().background(LibraryColors.Background)
            .padding(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding() + 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = actions.back), contentAlignment = Alignment.Center) {
                Icon(LibraryIcons.Back, stringResource(R.string.audio_back), tint = LibraryColors.OnBackground, modifier = Modifier.size(22.dp))
            }
            Text(
                stringResource(R.string.audio_day_times, dayLabel(recording.day), clockTime(recording.startMillis), clockTime(recording.endMillis)),
                color = LibraryColors.Muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(LibraryColors.Accent), contentAlignment = Alignment.Center) {
                Icon(kindIcon(recording.kind), null, tint = LibraryColors.PhotoText, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                soundTitle(recording, state.place),
                color = LibraryColors.OnBackground,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle().forNumber(recording),
            )
            // A call with both: the number under the name.
            if (recording.kind == AudioKind.CALL && recording.contact != null && recording.number != null) {
                Text(recording.number!!, color = LibraryColors.Muted, fontSize = 15.sp, style = TextStyle(textDirection = TextDirection.Ltr))
            }
            if (recording.kind == AudioKind.CALL) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recording.number?.let { number ->
                        OutlinedButton(onClick = { actions.dial(number) }) {
                            Icon(AudioIcons.Call, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.audio_dial), color = LibraryColors.OnBackground)
                        }
                    }
                    OutlinedButton(onClick = { editing = true }) {
                        Icon(LibraryIcons.Edit, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.audio_edit_contact), color = LibraryColors.OnBackground)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Waveform(
            recording,
            position.toFloat() / length,
            { actions.seek((it * recording.durationMillis).toLong()) },
            Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 20.dp),
            marks = photos.map { (it.takenAtMillis - recording.startMillis).toFloat() / length },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text(formatDuration(position), color = LibraryColors.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.audio_remaining, formatDuration((recording.durationMillis - position).coerceAtLeast(0))), color = LibraryColors.Muted, fontSize = 13.sp)
        }
        AudioControls(playback, recording, actions, Modifier.fillMaxWidth().padding(vertical = 12.dp))
        if (photos.isNotEmpty()) {
            Text(
                pluralStringResource(R.plurals.audio_photos_during, photos.size, photos.size),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyRow(state = strip, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(photos, key = { _, it -> it.id }) { i, item ->
                    val current = i == nowIndex
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(84.dp).clip(RoundedCornerShape(10.dp))
                                .then(if (current) Modifier.border(3.dp, LibraryColors.Warning, RoundedCornerShape(10.dp)) else Modifier)
                                .clickable { actions.seek(item.takenAtMillis - recording.startMillis) },
                        ) {
                            TwoStepPhoto(item, 240, 240, Modifier.fillMaxSize())
                        }
                        Text(clockTime(item.takenAtMillis), color = if (current) LibraryColors.Warning else LibraryColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
    if (editing) {
        EditCallDialog(recording, onDismiss = { editing = false }) { contact, number ->
            editing = false
            actions.setCall(contact, number)
        }
    }
}

/** Back 15 s, play or pause, forward 15 s, and the speed (1×, 1.5×, 2×, then 1× again). */
@Composable
private fun AudioControls(playback: AudioPlayback?, recording: SoundRecording, actions: AudioPageActions, modifier: Modifier = Modifier) {
    val position = playback?.positionMillis ?: 0L
    val playing = playback?.playing == true
    val speed = playback?.speed ?: 1f
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        val back = stringResource(R.string.audio_back_15)
        Box(Modifier.size(52.dp).clip(CircleShape).clickable { actions.seek((position - SKIP_MILLIS).coerceAtLeast(0)) }.semantics { contentDescription = back }, contentAlignment = Alignment.Center) {
            Icon(AudioIcons.Back15, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(28.dp))
            Text("15", color = LibraryColors.OnBackground, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        PlayButton(playing, if (playing) actions.pause else actions.play, size = 68.dp)
        val forward = stringResource(R.string.audio_forward_15)
        Box(Modifier.size(52.dp).clip(CircleShape).clickable { actions.seek((position + SKIP_MILLIS).coerceAtMost(recording.durationMillis)) }.semantics { contentDescription = forward }, contentAlignment = Alignment.Center) {
            Icon(AudioIcons.Forward15, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(28.dp))
            Text("15", color = LibraryColors.OnBackground, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            stringResource(R.string.audio_speed, if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()),
            color = LibraryColors.OnBackground,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = playback != null) { actions.setSpeed(nextSpeed(speed)) }
                .border(1.dp, LibraryColors.Muted.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** Who a call was with: the contact and the number, as the phone named the file; either may be left empty. */
@Composable
private fun EditCallDialog(recording: SoundRecording, onDismiss: () -> Unit, onSave: (contact: String, number: String) -> Unit) {
    var contact by remember { mutableStateOf(recording.contact.orEmpty()) }
    var number by remember { mutableStateOf(recording.number.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_edit_contact)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(contact, { contact = it }, label = { Text(stringResource(R.string.audio_contact)) }, singleLine = true)
                OutlinedTextField(number, { number = it }, label = { Text(stringResource(R.string.audio_number)) }, singleLine = true, textStyle = TextStyle(textDirection = TextDirection.Ltr))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(contact, number) }) { Text(stringResource(R.string.audio_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.audio_cancel)) } },
    )
}

private const val SKIP_MILLIS = 15_000L

private fun nextSpeed(speed: Float) = when (speed) {
    1f -> 1.5f
    1.5f -> 2f
    else -> 1f
}
