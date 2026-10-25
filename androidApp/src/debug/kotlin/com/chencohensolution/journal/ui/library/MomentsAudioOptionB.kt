package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaItem

// Option b, "header strip": a recording is a slim player bar that stays pinned at the top while its photos scroll
// under it, and each photo says how far into the recording it was taken. No rail; the bar leaves when the last
// photo of the recording does.

/** Moments with sound recordings, as option b draws them. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MomentsAudioScreenB(state: AudioMomentsState, actions: AudioMomentsActions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    val playback = state.playback
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        // Padded rather than given content padding, so the pinned bar stays below the status bar.
        LazyColumn(Modifier.padding(top = insets.calculateTopPadding()), contentPadding = PaddingValues(bottom = insets.calculateBottomPadding() + 24.dp)) {
            state.rows.forEachIndexed { i, row ->
                when (row) {
                    is AudioFeedRow.Day -> item(key = i) { AudioDayLine(row.label) }
                    is AudioFeedRow.Photos -> item(key = i) {
                        AudioMomentLabel(row.place, stringResource(R.string.audio_time_range, clockTime(row.items.minOf { it.takenAtMillis }), clockTime(row.items.maxOf { it.takenAtMillis })))
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val width = maxWidth
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.items.forEach { AudioFeedPicture(it, width, Modifier.combinedClickable(onClick = { actions.openPhoto(it) })) }
                            }
                        }
                    }
                    is AudioFeedRow.Recording -> {
                        stickyHeader(key = "bar$i") { RecordingBar(row.clip, playback, actions) }
                        items(row.during.size, key = { "$i/$it" }) { n -> OffsetPhoto(row.during[n], row.clip, actions) }
                        if (row.during.isEmpty()) item(key = "none$i") { Spacer(Modifier.height(4.dp)) }
                    }
                    is AudioFeedRow.Calls -> item(key = i) { CallsRow(row.calls, playback, actions) }
                    is AudioFeedRow.VoiceMessage -> item(key = i) { VoiceMessageRow(row.clip, playback, actions) }
                }
            }
        }
    }
}

/** The pinned bar: play, "1:45:12 · 18:10 – 19:55", what it is, and a line for how far it has played. */
@Composable
private fun RecordingBar(clip: AudioClip, playback: AudioPlayback?, actions: AudioMomentsActions) {
    val mine = playback?.clipId == clip.id
    val playing = mine && playback!!.playing
    Column(Modifier.fillMaxWidth().background(LibraryColors.Background).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LibraryColors.Raised).combinedClickable(onClick = { actions.openClip(clip) })) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayButton(playing, { if (playing) actions.pause() else actions.play(clip) }, size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(kindIcon(clip.kind), null, tint = LibraryColors.Muted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(clipTitle(clip), color = LibraryColors.OnBackground, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle().forNumber(clip))
                    }
                    Text(
                        if (mine) stringResource(R.string.audio_position, formatDuration(playback!!.positionMillis), formatDuration(clip.durationMillis), clockTime(clip.startMillis + playback.positionMillis)) else clipTimes(clip),
                        color = LibraryColors.Muted,
                        fontSize = 12.sp,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(LibraryColors.Muted.copy(alpha = 0.2f))) {
                if (mine) Box(Modifier.fillMaxWidth(playback!!.positionMillis.toFloat() / clip.durationMillis).height(3.dp).background(LibraryColors.OnBackground))
            }
        }
    }
}

/** A photo inside a recording, with how far into it it was taken ("+32:10"); a long press plays from there. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OffsetPhoto(item: MediaItem, clip: AudioClip, actions: AudioMomentsActions) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        AudioFeedPicture(
            item,
            maxWidth,
            Modifier.combinedClickable(
                onClick = { actions.openPhoto(item) },
                onLongClick = { actions.seek(clip, item.takenAtMillis - clip.startMillis) },
                onLongClickLabel = stringResource(R.string.audio_play_from_here),
            ),
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(10.dp).background(LibraryColors.BadgeScrim, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(LibraryColors.Warning))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.audio_offset, formatDuration(item.takenAtMillis - clip.startMillis)), color = LibraryColors.PhotoText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
