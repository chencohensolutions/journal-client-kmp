package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.SoundRecording
import com.chencohensolution.journal.stories.FeedMoment

// A recording in Moments ("header strip", chosen in the UI lab on Oct 25, 2026): a slim player bar where its moment
// starts, pinned under the title while the photos taken during it scroll by (JournalHomeScreen), each photo saying
// how far into the recording it was taken. See docs/screens/moments.md.

/**
 * A recording's bar: play, what it is ("Recording · Jaffa Port", the moment's place), its times or where it plays,
 * and a line for how far it has played. Tapping it opens the recording's page.
 */
@Composable
internal fun RecordingBar(moment: FeedMoment, audio: FeedAudio, modifier: Modifier = Modifier) {
    val recording = moment.recording ?: return
    val playback = audio.of(recording)
    val playing = playback?.playing == true
    Column(modifier.fillMaxWidth().background(LibraryColors.Background).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LibraryColors.Raised).clickable { audio.open(recording) }) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayButton(playing, { audio.toggle(recording) }, size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(kindIcon(recording.kind), null, tint = LibraryColors.Muted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            soundTitle(recording, moment.place),
                            color = LibraryColors.OnBackground,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle().forNumber(recording),
                        )
                    }
                    Text(
                        if (playback != null) {
                            stringResource(
                                R.string.audio_position,
                                formatDuration(playback.positionMillis),
                                formatDuration(recording.durationMillis),
                                clockTime(recording.startMillis + playback.positionMillis),
                            )
                        } else {
                            soundTimes(recording)
                        },
                        color = LibraryColors.Muted,
                        fontSize = 12.sp,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(LibraryColors.Muted.copy(alpha = 0.2f))) {
                if (playback != null && recording.durationMillis > 0) {
                    Box(Modifier.fillMaxWidth((playback.positionMillis.toFloat() / recording.durationMillis).coerceIn(0f, 1f)).height(3.dp).background(LibraryColors.OnBackground))
                }
            }
        }
    }
}

/** On a photo taken during [recording]: how far into it ("+32:10"). */
@Composable
internal fun RecordingOffset(item: MediaItem, recording: SoundRecording, modifier: Modifier = Modifier) {
    Row(
        modifier.background(LibraryColors.BadgeScrim, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(LibraryColors.Warning))
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.audio_offset, formatDuration((item.takenAtMillis - recording.startMillis).coerceAtLeast(0))),
            color = LibraryColors.PhotoText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
