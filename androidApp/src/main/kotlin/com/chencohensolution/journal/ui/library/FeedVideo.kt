package com.chencohensolution.journal.ui.library

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.stories.FeedBlock

// Videos in the Unsorted feed play by themselves, muted, while they are the one most in view. See docs/screens/unsorted.md.

/** Which video of the feed plays, and its sound: one setting for the whole feed, so unmuting carries to the next. */
internal class FeedVideo(
    val playingId: String? = null,
    val muted: Boolean = true,
    val toggleMute: () -> Unit = {},
)

/**
 * The video of [rows] to play: of the videos at least [MIN_SHOWN] in view (of their height, or of the screen for one
 * taller than it), the one closest to the middle of the uncovered part of the screen, between the top bar and the
 * bottom controls. Rows are the list's last items.
 */
internal fun LazyListState.videoToPlay(rows: List<FeedRow>): String? {
    val info = layoutInfo
    val top = 0
    val bottom = info.viewportEndOffset - info.afterContentPadding
    val first = info.totalItemsCount - rows.size
    val middle = (top + bottom) / 2
    return info.visibleItemsInfo
        .mapNotNull { item ->
            val video = ((rows.getOrNull(item.index - first) as? FeedRow.Photo)?.block as? FeedBlock.Single)?.item
                ?.takeIf { it.type == MediaType.VIDEO } ?: return@mapNotNull null
            val shown = minOf(item.offset + item.size, bottom) - maxOf(item.offset, top)
            if (shown < MIN_SHOWN * minOf(item.size, bottom - top)) return@mapNotNull null
            video to kotlin.math.abs(item.offset + item.size / 2 - middle)
        }
        .minByOrNull { it.second }
        ?.first?.id
}

private const val MIN_SHOWN = 0.6f

/**
 * [item] playing in a loop with [muted] sound, under [poster] until its first frame is drawn, and [controls] (the
 * mute button) over it; a video that can't be played stays its poster, without controls. Muted, it doesn't ask for audio focus, so music from another app keeps playing; with sound it
 * asks for it while it plays, and mutes itself ([onMute]) when another app takes it back.
 */
@Composable
internal fun InlineVideo(
    item: MediaItem,
    muted: Boolean,
    onMute: () -> Unit,
    poster: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var started by remember(item.id) { mutableStateOf(false) }
    var failed by remember(item.id) { mutableStateOf(false) }
    val player = remember(item.id) { InlinePlayer(context, item, onFirstFrame = { started = true }, onFailed = { failed = true }) }
    DisposableEffect(player) { onDispose { player.release() } }

    val currentOnMute by rememberUpdatedState(onMute)
    LaunchedEffect(player, muted) { player.setMuted(muted) { currentOnMute() } }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { TextureView(it).apply { surfaceTextureListener = player } },
            modifier = Modifier.fillMaxSize(),
        )
        if (!started || failed) poster()
        if (!failed) controls()
    }
}

/** The mute button over a playing video. */
@Composable
internal fun MuteButton(muted: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onToggle, modifier = modifier.size(40.dp)) {
        Box(Modifier.size(32.dp).background(LibraryColors.BadgeScrim, CircleShape), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Icon(
                if (muted) SoundIcons.Off else SoundIcons.On,
                stringResource(if (muted) R.string.feed_unmute else R.string.feed_mute),
                tint = LibraryColors.PhotoText,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** A MediaPlayer drawing on a TextureView: prepared once its surface is there, then looped. */
private class InlinePlayer(
    context: Context,
    item: MediaItem,
    private val onFirstFrame: () -> Unit,
    private val onFailed: () -> Unit,
) : TextureView.SurfaceTextureListener {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val player = MediaPlayer()
    private var surface: Surface? = null
    private var prepared = false
    private var failed = false
        set(value) {
            field = value
            if (value) onFailed()
        }
    private var focus: AudioFocusRequest? = null

    init {
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
            )
            player.setDataSource(context, item.contentUri())
            player.isLooping = true
            player.setVolume(0f, 0f)
            player.setOnPreparedListener {
                prepared = true
                it.start()
            }
            player.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onFirstFrame()
                false
            }
            // The poster stays; the user can still open the viewer.
            player.setOnErrorListener { _, _, _ ->
                failed = true
                true
            }
        } catch (e: Exception) {
            failed = true
        }
    }

    fun setMuted(muted: Boolean, onFocusLost: () -> Unit) {
        if (failed) return
        if (muted) {
            abandonFocus()
        } else if (focus == null) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener { change -> if (change < 0) onFocusLost() }
                .build()
            focus = request
            audio.requestAudioFocus(request)
        }
        val volume = if (muted) 0f else 1f
        player.setVolume(volume, volume)
    }

    fun release() {
        abandonFocus()
        player.release()
        surface?.release()
        surface = null
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (failed) return
        val newSurface = Surface(texture)
        surface = newSurface
        try {
            player.setSurface(newSurface)
            if (!prepared) player.prepareAsync()
        } catch (e: Exception) {
            failed = true
        }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        if (!failed) runCatching { player.setSurface(null) }
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
}

/** Speaker icons, drawn like [LibraryIcons]. */
private object SoundIcons {
    private fun icon(name: String, vararg paths: String) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { data ->
                addPath(
                    pathData = PathParser().parsePathString(data).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Off = icon("SoundOff", "M11 5L6 9H2v6h4l5 4V5z", "M22 9l-6 6", "M16 9l6 6")
    val On = icon("SoundOn", "M11 5L6 9H2v6h4l5 4V5z", "M15.5 8.5a5 5 0 0 1 0 7", "M19 5a10 10 0 0 1 0 14")
}
