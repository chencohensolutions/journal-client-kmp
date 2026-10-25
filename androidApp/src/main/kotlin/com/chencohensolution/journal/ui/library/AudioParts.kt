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
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.SoundRecording
import java.time.Instant
import java.time.ZoneId

// What Moments and a recording's page draw sound recordings with: icons, the play button, the waveform, calls and
// voice messages. See docs/screens/moments.md.

/** What the feed's sound recordings can ask for, and what plays ([AudioPlayer]); a no-op where nothing plays. */
@Stable
internal class FeedAudio(
    /** Read inside the composables that show it, so only they redraw as it plays. */
    val playback: () -> AudioPlayback? = { null },
    val play: (SoundRecording) -> Unit = {},
    val pause: () -> Unit = {},
    val seek: (SoundRecording, Long) -> Unit = { _, _ -> },
    /** The recording's page. */
    val open: (SoundRecording) -> Unit = {},
) {
    /** Where [recording] is, while it is the one playing or paused. */
    fun of(recording: SoundRecording): AudioPlayback? = playback()?.takeIf { it.sha256 == recording.sha256 }

    fun toggle(recording: SoundRecording) {
        if (of(recording)?.playing == true) pause() else play(recording)
    }
}

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

/** What a recording is called: a call's contact, or its number, else its kind ("Recording · Jaffa Port"). */
@Composable
internal fun soundTitle(recording: SoundRecording, place: String? = null): String = when (recording.kind) {
    AudioKind.CALL -> recording.contact ?: recording.number ?: stringResource(R.string.audio_call_unknown)
    AudioKind.VOICE_MESSAGE -> stringResource(R.string.audio_voice_message)
    AudioKind.RECORDING -> place?.let { stringResource(R.string.audio_recording_at, it) } ?: stringResource(R.string.audio_recording)
}

/** A number reads left to right also in Hebrew: "+972 50…" must not turn around. */
internal fun TextStyle.forNumber(recording: SoundRecording): TextStyle =
    if (recording.contact == null && recording.number != null) copy(textDirection = TextDirection.Ltr) else this

/** "18:10 – 19:55 · 1:45:12". */
@Composable
internal fun soundTimes(recording: SoundRecording): String =
    stringResource(R.string.audio_times, clockTime(recording.startMillis), clockTime(recording.endMillis), formatDuration(recording.durationMillis))

internal fun kindIcon(kind: AudioKind): ImageVector = when (kind) {
    AudioKind.RECORDING -> AudioIcons.Mic
    AudioKind.CALL -> AudioIcons.Call
    AudioKind.VOICE_MESSAGE -> AudioIcons.Voice
}

/** The round play or pause button. Nothing plays until it is tapped (or a photo inside a recording is held). */
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

/** How many bars the waveform draws. */
private const val WAVE_BARS = 64

/**
 * [recording] as bars, what has played darker; a tap seeks there. The server doesn't measure loudness yet, so the bars
 * are even. [marks] are moments to show under it (when photos were taken), as fractions of its length.
 */
@Composable
internal fun Waveform(
    recording: SoundRecording,
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
        modifier.pointerInput(recording.sha256, rtl) {
            detectTapGestures { at ->
                val f = (at.x / size.width).coerceIn(0f, 1f)
                onSeek(if (rtl) 1f - f else f)
            }
        },
    ) {
        val markSpace = if (marks.isEmpty()) 0f else 8.dp.toPx()
        val h = size.height - markSpace
        val step = size.width / WAVE_BARS
        val bar = (step * 0.6f).coerceAtLeast(1f)
        val barH = h * 0.5f
        repeat(WAVE_BARS) { i ->
            val f = (i + 0.5f) / WAVE_BARS
            val x = if (rtl) size.width - (i + 1) * step else i * step
            drawRoundRect(
                if (progress != null && f <= progress) played else rest,
                topLeft = Offset(x + (step - bar) / 2, (h - barH) / 2),
                size = Size(bar, barH),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
        marks.forEach { mark ->
            val m = mark.coerceIn(0f, 1f)
            val x = (if (rtl) 1f - m else m) * size.width
            drawCircle(markColor, radius = 2.5.dp.toPx(), center = Offset(x, size.height - 3.dp.toPx()))
        }
    }
}

/** One call: who, when and how long, and play. Tapping it opens its page. */
@Composable
internal fun CallRow(call: SoundRecording, audio: FeedAudio, modifier: Modifier = Modifier) {
    val playing = audio.of(call)?.playing == true
    Row(
        modifier.fillMaxWidth().clickable { audio.open(call) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AudioIcons.Call, null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                soundTitle(call),
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
        PlayButton(playing, { audio.toggle(call) }, size = 36.dp)
    }
}

/** Calls one after another: one line ("📞 4 calls · 09:10 – 11:40") until tapped, then each call. */
@Composable
internal fun CallsRow(calls: List<SoundRecording>, audio: FeedAudio) {
    var open by remember(calls.last().sha256) { mutableStateOf(calls.size == 1) }
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
            calls.forEachIndexed { i, call ->
                if (i > 0 || calls.size > 1) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.15f))
                CallRow(call, audio)
            }
        }
    }
}

/** A WhatsApp voice message: a bubble with play, its bars and its length. */
@Composable
internal fun VoiceMessageRow(recording: SoundRecording, audio: FeedAudio) {
    val playback = audio.of(recording)
    val playing = playback?.playing == true
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            Modifier.width(260.dp)
                .background(LibraryColors.Raised, RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp))
                .clickable { audio.open(recording) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PlayButton(playing, { audio.toggle(recording) }, size = 34.dp)
            Column(Modifier.weight(1f)) {
                Waveform(
                    recording,
                    playback?.let { it.positionMillis.toFloat() / recording.durationMillis.coerceAtLeast(1) },
                    { audio.seek(recording, (it * recording.durationMillis).toLong()) },
                    Modifier.fillMaxWidth().height(26.dp),
                )
                Text(
                    stringResource(R.string.audio_voice_line, formatDuration(recording.durationMillis), clockTime(recording.startMillis)),
                    color = LibraryColors.Muted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
