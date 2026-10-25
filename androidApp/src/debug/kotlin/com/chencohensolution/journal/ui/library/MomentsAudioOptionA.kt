package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

// Option a, "span rail": a recording is a card where it ended (the feed is newest first), and a rail runs down the
// leading edge from it past every photo taken while it ran, to a cap where it started. While it plays a dot moves down
// the rail and the photo of that moment is marked; a long press on a photo plays from when it was taken.

/** Moments with sound recordings, as option a draws them. */
@Composable
internal fun MomentsAudioScreenA(state: AudioMomentsState, actions: AudioMomentsActions) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val insets = WindowInsets.systemBars.asPaddingValues()
    val playback = state.playback
    val playingRow = state.rows.indexOfFirst { it is AudioFeedRow.Recording && it.clip.id == playback?.clipId }
    // The player docks at the bottom once the card of what plays is scrolled away.
    val cardShown by remember(playingRow) {
        derivedStateOf { playingRow < 0 || list.layoutInfo.visibleItemsInfo.any { it.index == playingRow } }
    }
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyColumn(state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding() + 96.dp)) {
            state.rows.forEachIndexed { i, row ->
                item(key = i) {
                    when (row) {
                        is AudioFeedRow.Day -> AudioDayLine(row.label)
                        is AudioFeedRow.Photos -> PhotosA(row, actions)
                        is AudioFeedRow.Recording -> RecordingSpanA(row, playback, actions)
                        is AudioFeedRow.Calls -> CallsRow(row.calls, playback, actions)
                        is AudioFeedRow.VoiceMessage -> VoiceMessageRow(row.clip, playback, actions)
                    }
                }
            }
        }
        val recording = state.rows.getOrNull(playingRow) as? AudioFeedRow.Recording
        if (recording != null && playback != null && !cardShown) {
            MiniPlayer(
                recording.clip,
                playback,
                actions,
                backToMoment = { scope.launch { list.animateScrollToItem(playingRow) } },
                Modifier.align(Alignment.BottomCenter).padding(bottom = insets.calculateBottomPadding() + 12.dp),
            )
        }
    }
}

@Composable
private fun PhotosA(row: AudioFeedRow.Photos, actions: AudioMomentsActions) {
    AudioMomentLabel(row.place, stringResource(R.string.audio_time_range, clockTime(row.items.minOf { it.takenAtMillis }), clockTime(row.items.maxOf { it.takenAtMillis })))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            row.items.forEach { item ->
                AudioFeedPicture(item, width, Modifier.combinedClickable(onClick = { actions.openPhoto(item) }))
            }
        }
    }
}

/** How far the rail sits from the edge, and how wide it is. */
private val RAIL_INSET = 22.dp
private val RAIL_WIDTH = 3.dp

/** The card, then the photos taken during the recording along its rail, then where it started. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingSpanA(row: AudioFeedRow.Recording, playback: AudioPlayback?, actions: AudioMomentsActions) {
    val clip = row.clip
    val mine = playback?.clipId == clip.id
    val at = if (mine) clip.startMillis + playback!!.positionMillis else null
    // The photo of the moment playing: the last one taken by then.
    val now = at?.let { t -> row.during.filter { it.takenAtMillis <= t }.maxByOrNull { it.takenAtMillis } }
    val rail = LibraryColors.Accent
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        RecordingCard(clip, row.during, playback, actions, Modifier.padding(horizontal = 12.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val photoWidth = maxWidth - RAIL_INSET * 2 - 8.dp
            Column {
                row.during.forEach { item ->
                    val current = item == now
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        RailSegment(rail, dot = if (current) 12.dp else 7.dp, dotColor = if (current) LibraryColors.Warning else rail)
                        Box(Modifier.padding(top = 6.dp, bottom = 6.dp)) {
                            AudioFeedPicture(
                                item,
                                photoWidth,
                                Modifier.clip(RoundedCornerShape(12.dp))
                                    .then(if (current) Modifier.border(3.dp, LibraryColors.Warning, RoundedCornerShape(12.dp)) else Modifier)
                                    .combinedClickable(
                                        onClick = { actions.openPhoto(item) },
                                        onLongClick = { actions.seek(clip, item.takenAtMillis - clip.startMillis) },
                                        onLongClickLabel = stringResource(R.string.audio_play_from_here),
                                    ),
                            )
                            PhotoMoment(item, clip, current, Modifier.align(Alignment.TopStart).padding(8.dp))
                        }
                    }
                }
                // Where it started: the rail's end.
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(RAIL_INSET * 2).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.width(RAIL_WIDTH).height(14.dp).background(rail))
                        Box(Modifier.padding(top = 10.dp).size(12.dp).clip(CircleShape).background(rail))
                    }
                    Text(
                        stringResource(R.string.audio_started, clockTime(clip.startMillis)),
                        color = LibraryColors.Muted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}

/** A piece of the rail beside one photo, with a dot at its top for when the photo was taken. */
@Composable
private fun RailSegment(rail: androidx.compose.ui.graphics.Color, dot: androidx.compose.ui.unit.Dp, dotColor: androidx.compose.ui.graphics.Color) {
    Box(Modifier.width(RAIL_INSET * 2).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.width(RAIL_WIDTH).fillMaxHeight().background(rail))
        Box(Modifier.padding(top = 18.dp - dot / 2).size(dot).clip(CircleShape).background(dotColor))
    }
}

/** On a photo inside a recording: when it was taken, and how far into the recording ("18:42 · +32:10"), or Now. */
@Composable
private fun PhotoMoment(item: MediaItem, clip: AudioClip, current: Boolean, modifier: Modifier) {
    val text = if (current) {
        stringResource(R.string.audio_now)
    } else {
        stringResource(R.string.audio_photo_moment, clockTime(item.takenAtMillis), formatDuration(item.takenAtMillis - clip.startMillis))
    }
    Text(
        text,
        color = if (current) LibraryColors.OnInverse else LibraryColors.PhotoText,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.background(if (current) LibraryColors.Warning else LibraryColors.BadgeScrim, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * A recording's card: what it is, from when to when and how long, how many photos were taken meanwhile, play, and the
 * waveform with a mark for each photo. Tapping the card opens the recording's page.
 */
@Composable
internal fun RecordingCard(clip: AudioClip, during: List<MediaItem>, playback: AudioPlayback?, actions: AudioMomentsActions, modifier: Modifier = Modifier) {
    val mine = playback?.clipId == clip.id
    val playing = mine && playback!!.playing
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(LibraryColors.Raised)
            .combinedClickable(onClick = { actions.openClip(clip) }).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(LibraryColors.Accent), contentAlignment = Alignment.Center) {
                Icon(kindIcon(clip.kind), null, tint = LibraryColors.PhotoText, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(clipTitle(clip), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle().forNumber(clip))
                Text(clipTimes(clip), color = LibraryColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            PlayButton(playing, { if (playing) actions.pause() else actions.play(clip) })
        }
        Spacer(Modifier.height(12.dp))
        Waveform(
            clip,
            if (mine) playback!!.positionMillis.toFloat() / clip.durationMillis else null,
            { actions.seek(clip, (it * clip.durationMillis).toLong()) },
            Modifier.fillMaxWidth().height(40.dp),
            marks = during.map { (it.takenAtMillis - clip.startMillis).toFloat() / clip.durationMillis },
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(
                if (mine) formatDuration(playback!!.positionMillis) else clockTime(clip.startMillis),
                color = LibraryColors.Muted,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            if (during.isNotEmpty()) {
                Text(
                    androidx.compose.ui.res.pluralStringResource(R.plurals.audio_photos_during, during.size, during.size),
                    color = LibraryColors.Muted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
