package com.chencohensolution.journal.ui.library

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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

// Viewer option b, "slideshow": the photos taken while it recorded fill the screen, each shown while the recording
// passes the moment it was taken, and the player is a sheet at the bottom. A recording with no photos shows its
// card instead.

/** One recording's page, as viewer option b draws it. */
@Composable
internal fun AudioViewerScreenB(state: AudioViewerState, actions: AudioViewerActions) {
    val clip = state.clip
    val playback = state.playback?.takeIf { it.clipId == clip.id }
    val position = playback?.positionMillis ?: 0L
    val photos = state.during.sortedBy { it.takenAtMillis }
    val now = photos.lastOrNull { it.takenAtMillis <= clip.startMillis + position } ?: photos.firstOrNull()
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Crossfade(now, Modifier.fillMaxSize(), label = "photo") { item ->
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (item != null) {
                    AudioFeedPicture(item, if (item.ratio >= maxWidth / maxHeight) maxWidth else maxHeight * item.ratio)
                } else {
                    Box(Modifier.size(120.dp).clip(CircleShape).background(LibraryColors.Accent), contentAlignment = Alignment.Center) {
                        Icon(kindIcon(clip.kind), null, tint = LibraryColors.PhotoText, modifier = Modifier.size(56.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(LibraryColors.TopFade).padding(top = insets.calculateTopPadding()).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = actions.back), contentAlignment = Alignment.Center) {
                Icon(LibraryIcons.Back, stringResource(R.string.audio_back), tint = LibraryColors.OnBackground, modifier = Modifier.size(22.dp))
            }
            now?.let { Text(stringResource(R.string.audio_photo_moment, clockTime(it.takenAtMillis), formatDuration(it.takenAtMillis - clip.startMillis)), color = LibraryColors.OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(LibraryColors.Raised)
                .padding(top = 10.dp, bottom = insets.calculateBottomPadding() + 8.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(RoundedCornerShape(50)).background(LibraryColors.Muted.copy(alpha = 0.5f)))
            Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(kindIcon(clip.kind), null, tint = LibraryColors.Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(clipTitle(clip), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle().forNumber(clip))
                    Text(clipTimes(clip), color = LibraryColors.Muted, fontSize = 13.sp)
                }
            }
            Waveform(
                clip,
                position.toFloat() / clip.durationMillis,
                { actions.seek((it * clip.durationMillis).toLong()) },
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 20.dp),
                marks = photos.map { (it.takenAtMillis - clip.startMillis).toFloat() / clip.durationMillis },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(formatDuration(position), color = LibraryColors.OnBackground, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.audio_remaining, formatDuration(clip.durationMillis - position)), color = LibraryColors.Muted, fontSize = 12.sp)
            }
            AudioControls(playback, clip, actions, Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }
    }
}
