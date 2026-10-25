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
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

// Viewer option a, "filmstrip": a recording's page in the viewer. The waveform large, with a mark at each photo's
// moment; the controls; and under them the photos taken while it ran, the one of the moment playing marked. Tapping a
// mark or a photo plays from there. A call's page names who it was with, with Call and Edit contact.

/** One recording's page, as viewer option a draws it. Always dark, like the viewer. */
@Composable
internal fun AudioViewerScreenA(state: AudioViewerState, actions: AudioViewerActions) {
    val clip = state.clip
    val playback = state.playback?.takeIf { it.clipId == clip.id }
    val position = playback?.positionMillis ?: 0L
    val photos = state.during.sortedBy { it.takenAtMillis }
    val nowIndex = photos.indexOfLast { it.takenAtMillis <= clip.startMillis + position }
    val strip = rememberLazyListState()
    LaunchedEffect(nowIndex) { if (nowIndex >= 0) strip.animateScrollToItem(nowIndex) }
    val insets = WindowInsets.systemBars.asPaddingValues()
    Column(
        Modifier.fillMaxSize().background(LibraryColors.Background)
            .padding(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding() + 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = actions.back), contentAlignment = Alignment.Center) {
                Icon(LibraryIcons.Back, stringResource(R.string.audio_back), tint = LibraryColors.OnBackground, modifier = Modifier.size(22.dp))
            }
            Text(
                stringResource(R.string.audio_day_times, clockTime(clip.startMillis), clockTime(clip.endMillis)),
                color = LibraryColors.Muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(LibraryColors.Accent), contentAlignment = Alignment.Center) {
                Icon(kindIcon(clip.kind), null, tint = LibraryColors.PhotoText, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                clipTitle(clip),
                color = LibraryColors.OnBackground,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle().forNumber(clip),
            )
            // A call with both: the number under the name.
            if (clip.kind == AudioKind.CALL && clip.contact != null && clip.number != null) {
                Text(clip.number, color = LibraryColors.Muted, fontSize = 15.sp, style = TextStyle(textDirection = TextDirection.Ltr))
            }
            if (clip.kind == AudioKind.CALL) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    clip.number?.let { number ->
                        OutlinedButton(onClick = { actions.dial(number) }) {
                            Icon(AudioIcons.Call, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.audio_dial), color = LibraryColors.OnBackground)
                        }
                    }
                    OutlinedButton(onClick = actions.editContact) {
                        Icon(LibraryIcons.Edit, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.audio_edit_contact), color = LibraryColors.OnBackground)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Waveform(
            clip,
            position.toFloat() / clip.durationMillis,
            { actions.seek((it * clip.durationMillis).toLong()) },
            Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 20.dp),
            marks = photos.map { (it.takenAtMillis - clip.startMillis).toFloat() / clip.durationMillis },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text(formatDuration(position), color = LibraryColors.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.audio_remaining, formatDuration(clip.durationMillis - position)), color = LibraryColors.Muted, fontSize = 13.sp)
        }
        AudioControls(playback, clip, actions, Modifier.fillMaxWidth().padding(vertical = 12.dp))
        if (photos.isNotEmpty()) {
            Text(
                androidx.compose.ui.res.pluralStringResource(R.plurals.audio_photos_during, photos.size, photos.size),
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
                                .clickable { actions.seek(item.takenAtMillis - clip.startMillis) },
                        ) {
                            TwoStepPhoto(item, 240, 240, Modifier.fillMaxSize())
                        }
                        Text(clockTime(item.takenAtMillis), color = if (current) LibraryColors.Warning else LibraryColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

/** Back 15 s, play or pause, forward 15 s, and the speed (1×, 1.5×, 2×, then 1× again). */
@Composable
internal fun AudioControls(playback: AudioPlayback?, clip: AudioClip, actions: AudioViewerActions, modifier: Modifier = Modifier) {
    val position = playback?.positionMillis ?: 0L
    val playing = playback?.playing == true
    val speed = playback?.speed ?: 1f
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        val back = stringResource(R.string.audio_back_15)
        Box(Modifier.size(52.dp).clip(CircleShape).clickable { actions.seek((position - 15_000).coerceAtLeast(0)) }.semantics { contentDescription = back }, contentAlignment = Alignment.Center) {
            Icon(AudioIcons.Back15, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(28.dp))
            Text("15", color = LibraryColors.OnBackground, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        PlayButton(playing, if (playing) actions.pause else actions.play, size = 68.dp)
        val forward = stringResource(R.string.audio_forward_15)
        Box(Modifier.size(52.dp).clip(CircleShape).clickable { actions.seek((position + 15_000).coerceAtMost(clip.durationMillis)) }.semantics { contentDescription = forward }, contentAlignment = Alignment.Center) {
            Icon(AudioIcons.Forward15, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(28.dp))
            Text("15", color = LibraryColors.OnBackground, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            stringResource(R.string.audio_speed, if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()),
            color = LibraryColors.OnBackground,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { actions.setSpeed(nextSpeed(speed)) }
                .border(1.dp, LibraryColors.Muted.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

private fun nextSpeed(speed: Float) = when (speed) {
    1f -> 1.5f
    1.5f -> 2f
    else -> 1f
}
