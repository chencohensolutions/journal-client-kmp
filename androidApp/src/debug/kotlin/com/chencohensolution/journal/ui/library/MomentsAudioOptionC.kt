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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R

// Option c, "audio as a set": a recording leads a set you swipe, like videos taken near each other: its card first,
// then the photos taken while it ran, oldest first. Compact, but the photos leave the feed's vertical flow; while it
// plays the set turns to the photo of that moment.

/** Moments with sound recordings, as option c draws them. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MomentsAudioScreenC(state: AudioMomentsState, actions: AudioMomentsActions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    val playback = state.playback
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyColumn(contentPadding = PaddingValues(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding() + 24.dp)) {
            state.rows.forEachIndexed { i, row ->
                item(key = i) {
                    when (row) {
                        is AudioFeedRow.Day -> AudioDayLine(row.label)
                        is AudioFeedRow.Photos -> {
                            AudioMomentLabel(row.place, stringResource(R.string.audio_time_range, clockTime(row.items.minOf { it.takenAtMillis }), clockTime(row.items.maxOf { it.takenAtMillis })))
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val width = maxWidth
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    row.items.forEach { AudioFeedPicture(it, width, Modifier.combinedClickable(onClick = { actions.openPhoto(it) })) }
                                }
                            }
                        }
                        is AudioFeedRow.Recording -> RecordingSet(row, playback, actions)
                        is AudioFeedRow.Calls -> CallsRow(row.calls, playback, actions)
                        is AudioFeedRow.VoiceMessage -> VoiceMessageRow(row.clip, playback, actions)
                    }
                }
            }
        }
    }
}

/** The card, then each photo taken while it recorded, in one pager of square pages, with dots under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingSet(row: AudioFeedRow.Recording, playback: AudioPlayback?, actions: AudioMomentsActions) {
    val photos = row.during.sortedBy { it.takenAtMillis }
    val pager = rememberPagerState { photos.size + 1 }
    // While it plays, the set turns to the photo of the moment playing.
    val at = playback?.takeIf { it.clipId == row.clip.id && it.playing }?.let { row.clip.startMillis + it.positionMillis }
    val target = at?.let { t -> photos.indexOfLast { it.takenAtMillis <= t } + 1 }
    LaunchedEffect(target) { if (target != null && target > 0) pager.animateScrollToPage(target) }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        HorizontalPager(pager, Modifier.fillMaxWidth().aspectRatio(1f)) { page ->
            if (page == 0) {
                Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                    RecordingCard(row.clip, row.during, playback, actions)
                }
            } else {
                val item = photos[page - 1]
                BoxWithConstraints(Modifier.fillMaxSize().background(LibraryColors.Raised), contentAlignment = Alignment.Center) {
                    AudioFeedPicture(item, if (item.ratio >= 1f) maxWidth else maxHeight * item.ratio, Modifier.combinedClickable(
                        onClick = { actions.openPhoto(item) },
                        onLongClick = { actions.seek(row.clip, item.takenAtMillis - row.clip.startMillis) },
                    ))
                    Text(
                        stringResource(R.string.audio_photo_moment, clockTime(item.takenAtMillis), formatDuration(item.takenAtMillis - row.clip.startMillis)),
                        color = LibraryColors.PhotoText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.TopStart).padding(10.dp).background(LibraryColors.BadgeScrim, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
            repeat(minOf(photos.size + 1, 8)) { i ->
                Box(
                    Modifier.padding(horizontal = 3.dp).size(if (i == pager.currentPage.coerceAtMost(7)) 8.dp else 6.dp).clip(CircleShape)
                        .background(if (i == pager.currentPage.coerceAtMost(7)) LibraryColors.OnBackground else LibraryColors.Muted.copy(alpha = 0.4f)),
                )
            }
        }
    }
}
