package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaItem
import java.time.Instant
import java.time.ZoneId

// The parts every option of the ux/moments-audio round draws sound recordings with: icons, the waveform, the play
// button, calls and voice messages, and the player that stays at the bottom while one plays. They ship with
// whichever option is chosen.

/** Line icons for sound recordings, drawn like [LibraryIcons]. */
internal object AudioIcons {
    private fun icon(name: String, filled: Boolean, vararg paths: String) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { data ->
                val nodes = PathParser().parsePathString(data).toNodes()
                if (filled) {
                    addPath(pathData = nodes, fill = SolidColor(Color.Black))
                } else {
                    addPath(nodes, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
                }
            }
        }.build()

    val Mic = icon("Mic", false, "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M5 11a7 7 0 0 0 14 0", "M12 18v3")
    val Call = icon("Call", false, "M5 4h3.5l2 5-2.5 1.5a11 11 0 0 0 5.5 5.5l1.5-2.5 5 2V19a2 2 0 0 1-2 2A17 17 0 0 1 3 6a2 2 0 0 1 2-2z")
    val Voice = icon("Voice", false, "M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z", "M9 10v4", "M12 8v8", "M15 10v4")
    val Play = icon("Play", true, "M8 5v14l11-7z")
    val Pause = icon("Pause", true, "M7 5h3.5v14H7z", "M13.5 5H17v14h-3.5z")
    val Back15 = icon("Back15", false, "M4 12a8 8 0 1 0 2.3-5.7", "M4 4v4.5h4.5")
    val Forward15 = icon("Forward15", false, "M20 12a8 8 0 1 1-2.3-5.7", "M20 4v4.5h-4.5")
}

/** "18:10", in this phone's time zone and format. */
internal fun clockTime(millis: Long): String = formatDate(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()), "jmm")

/** What a recording is called: a call's contact, or its number, else the kind ("Recording · Jaffa Port"). */
@Composable
internal fun clipTitle(clip: AudioClip): String = when (clip.kind) {
    AudioKind.CALL -> clip.contact ?: clip.number ?: stringResource(R.string.audio_call_unknown)
    AudioKind.VOICE_MESSAGE -> stringResource(R.string.audio_voice_message)
    AudioKind.RECORDING -> clip.place?.let { stringResource(R.string.audio_recording_at, it) } ?: stringResource(R.string.audio_recording)
}

/** A number reads left to right also in Hebrew: "+972 50…" must not turn around. */
internal fun TextStyle.forNumber(clip: AudioClip): TextStyle =
    if (clip.contact == null && clip.number != null) copy(textDirection = TextDirection.Ltr) else this

/** "18:10 – 19:55 · 1:45:12". */
@Composable
internal fun clipTimes(clip: AudioClip): String =
    stringResource(R.string.audio_times, clockTime(clip.startMillis), clockTime(clip.endMillis), formatDuration(clip.durationMillis))

internal fun kindIcon(kind: AudioKind): ImageVector = when (kind) {
    AudioKind.RECORDING -> AudioIcons.Mic
    AudioKind.CALL -> AudioIcons.Call
    AudioKind.VOICE_MESSAGE -> AudioIcons.Voice
}

/** The round play or pause button. Playing starts only from here (or from a photo inside a recording). */
@Composable
internal fun PlayButton(playing: Boolean, onClick: () -> Unit, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val label = stringResource(if (playing) R.string.audio_pause else R.string.audio_play)
    Box(
        modifier.size(size).clip(CircleShape).background(LibraryColors.OnBackground).clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playing) AudioIcons.Pause else AudioIcons.Play, null, tint = LibraryColors.Background, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * The loudness along [clip] as bars, what has played darker; a tap seeks there. [marks] are moments to show under it
 * (when photos were taken), as fractions of its length.
 */
@Composable
internal fun Waveform(
    clip: AudioClip,
    progress: Float?,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    marks: List<Float> = emptyList(),
    played: Color = LibraryColors.OnBackground,
    rest: Color = LibraryColors.Muted.copy(alpha = 0.45f),
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val markColor = LibraryColors.Warning
    Canvas(
        modifier.pointerInput(clip.id, rtl) {
            detectTapGestures { at ->
                val f = (at.x / size.width).coerceIn(0f, 1f)
                onSeek(if (rtl) 1f - f else f)
            }
        },
    ) {
        val peaks = clip.peaks.ifEmpty { List(48) { 0.3f } }
        val markSpace = if (marks.isEmpty()) 0f else 8.dp.toPx()
        val h = size.height - markSpace
        val step = size.width / peaks.size
        val bar = (step * 0.6f).coerceAtLeast(1f)
        peaks.forEachIndexed { i, p ->
            val f = (i + 0.5f) / peaks.size
            val x = if (rtl) size.width - (i + 1) * step else i * step
            val barH = (h * (0.15f + 0.85f * p)).coerceAtLeast(2f)
            drawRoundRect(
                if (progress != null && f <= progress) played else rest,
                topLeft = Offset(x + (step - bar) / 2, (h - barH) / 2),
                size = Size(bar, barH),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
        marks.forEach { m ->
            val x = (if (rtl) 1f - m else m) * size.width
            drawCircle(markColor, radius = 2.5.dp.toPx(), center = Offset(x, size.height - 3.dp.toPx()))
        }
    }
}

/** One call: who, when and how long, and play. A number with no name reads left to right. */
@Composable
internal fun CallRow(call: AudioClip, playback: AudioPlayback?, actions: AudioMomentsActions, modifier: Modifier = Modifier) {
    val playing = playback?.clipId == call.id && playback.playing
    Row(
        modifier.fillMaxWidth().clickable { actions.openClip(call) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AudioIcons.Call, null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                clipTitle(call),
                color = LibraryColors.OnBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle().forNumber(call),
            )
            Text(
                stringResource(R.string.audio_time_length, clockTime(call.startMillis), formatDuration(call.durationMillis)),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
            )
        }
        PlayButton(playing, { if (playing) actions.pause() else actions.play(call) }, size = 36.dp)
    }
}

/** Calls next to each other: one line ("📞 4 calls · 09:10 – 11:40") until tapped, then each call. */
@Composable
internal fun CallsRow(calls: List<AudioClip>, playback: AudioPlayback?, actions: AudioMomentsActions, startOpen: Boolean = false) {
    var open by remember(calls.first().id) { mutableStateOf(startOpen || calls.size == 1) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .background(LibraryColors.Raised, RoundedCornerShape(16.dp)),
    ) {
        if (calls.size > 1) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AudioIcons.Call, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(
                        R.string.audio_calls_line,
                        pluralStringResource(R.plurals.audio_calls, calls.size, calls.size),
                        clockTime(calls.minOf { it.startMillis }),
                        clockTime(calls.maxOf { it.endMillis }),
                    ),
                    color = LibraryColors.OnBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(LibraryIcons.ChevronDown, null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
            }
        }
        if (open) {
            calls.sortedByDescending { it.startMillis }.forEachIndexed { i, call ->
                if (i > 0 || calls.size > 1) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.15f))
                CallRow(call, playback, actions)
            }
        }
    }
}

/** A WhatsApp voice message: a bubble with its waveform and length. */
@Composable
internal fun VoiceMessageRow(clip: AudioClip, playback: AudioPlayback?, actions: AudioMomentsActions) {
    val mine = playback?.clipId == clip.id
    val playing = mine && playback!!.playing
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            Modifier.width(260.dp).background(LibraryColors.Raised, RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PlayButton(playing, { if (playing) actions.pause() else actions.play(clip) }, size = 34.dp)
            Column(Modifier.weight(1f)) {
                Waveform(clip, if (mine) playback!!.positionMillis.toFloat() / clip.durationMillis else null, { actions.seek(clip, (it * clip.durationMillis).toLong()) }, Modifier.fillMaxWidth().height(26.dp))
                Text(
                    stringResource(R.string.audio_voice_line, formatDuration(clip.durationMillis), clockTime(clip.startMillis)),
                    color = LibraryColors.Muted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * The player at the bottom of the feed while a recording plays and its card is scrolled away: what plays, where it is,
 * pause, and back to its moment. Closing it stops playing.
 */
@Composable
internal fun MiniPlayer(clip: AudioClip, playback: AudioPlayback, actions: AudioMomentsActions, backToMoment: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(18.dp))
            .background(LibraryColors.Inverse).clickable(onClick = backToMoment),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(kindIcon(clip.kind), null, tint = LibraryColors.OnInverse, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(clipTitle(clip), color = LibraryColors.OnInverse, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle().forNumber(clip))
                Text(
                    stringResource(R.string.audio_position, formatDuration(playback.positionMillis), formatDuration(clip.durationMillis), clockTime(clip.startMillis + playback.positionMillis)),
                    color = LibraryColors.OnInverseMuted,
                    fontSize = 12.sp,
                )
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(LibraryColors.OnInverse)
                    .clickable { if (playback.playing) actions.pause() else actions.play(clip) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (playback.playing) AudioIcons.Pause else AudioIcons.Play, stringResource(if (playback.playing) R.string.audio_pause else R.string.audio_play), tint = LibraryColors.Inverse, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = actions.stop), contentAlignment = Alignment.Center) {
                Icon(LibraryIcons.Close, stringResource(R.string.audio_stop), tint = LibraryColors.OnInverseMuted, modifier = Modifier.size(18.dp))
            }
        }
        // How far it has played, as a thin line along the bottom.
        Box(Modifier.fillMaxWidth().height(3.dp).background(LibraryColors.OnInverseMuted.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(playback.positionMillis.toFloat() / clip.durationMillis).height(3.dp).background(LibraryColors.OnInverse))
        }
    }
}

/** [item] at its own shape, [width] wide, sharp at that size. */
@Composable
internal fun AudioFeedPicture(item: MediaItem, width: Dp, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { width.roundToPx() }
    Box(modifier.width(width).aspectRatio(item.ratio).background(LibraryColors.Raised)) {
        TwoStepPhoto(item, px, (px / item.ratio).toInt(), Modifier.matchParentSize())
    }
}

/** The line between days, with the day on it, as the feed draws it. */
@Composable
internal fun AudioDayLine(label: String) {
    val line = LibraryColors.Muted.copy(alpha = 0.3f)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(line))
        Text(
            label,
            color = LibraryColors.OnBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp).background(LibraryColors.Raised, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(line))
    }
}

/** A camera moment's label: its place, then its time. */
@Composable
internal fun AudioMomentLabel(place: String?, time: String) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp)) {
        place?.let { Text(it, color = LibraryColors.OnBackground, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text(time, color = LibraryColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
