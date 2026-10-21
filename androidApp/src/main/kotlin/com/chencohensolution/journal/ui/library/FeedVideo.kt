package com.chencohensolution.journal.ui.library

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.stories.FeedBlock

// Videos in the Moments feed play by themselves, muted, while they are the one most in view. See docs/screens/moments.md.

/**
 * Which video of the feed plays, and its sound: one setting for the whole feed, so unmuting carries to the next. Both
 * are read through lambdas, so only the video items that read them recompose when they change.
 */
internal class FeedVideo(
    private val playingId: () -> String? = { null },
    private val isMuted: () -> Boolean = { true },
    val toggleMute: () -> Unit = {},
    /** The photo a set was last turned to, kept in its note across launches, and remembering it. */
    private val savedPage: (FeedBlock) -> String? = { null },
    private val savePage: (FeedBlock, itemId: String) -> Unit = { _, _ -> },
) {
    /** Videos only on the server play too, streamed into the video cache ([ServerVideos]). */
    fun isPlaying(item: MediaItem): Boolean = item.type == MediaType.VIDEO && playingId() == item.id

    val muted: Boolean get() = isMuted()

    /** The item each swipe set shows, by the set's key: a set plays the video it is turned to. */
    private val pages = mutableStateMapOf<String, String>()

    fun showPage(block: FeedBlock, itemId: String) {
        if (pages[block.key] == itemId) return
        // A set scrolling into view reports the page it opened on, which is already saved (or is its first): only a
        // turn is saved.
        val openedOn = if (block.key in pages) null else block.items[startPage(block)].id
        pages[block.key] = itemId
        if (itemId != openedOn) savePage(block, itemId)
    }

    /** The page [block] opens on: the one it was last turned to, else its first. */
    fun startPage(block: FeedBlock): Int =
        (pages[block.key] ?: savedPage(block))?.let { id -> block.items.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0

    /** The item [block] shows now: its page, or the only item. */
    fun shownIn(block: FeedBlock): MediaItem = pages[block.key]?.let { id -> block.items.firstOrNull { it.id == id } } ?: block.items.first()
}

/**
 * The video of [rows] to play: of the videos at least [MIN_SHOWN] in view (of their height, or of the screen for one
 * taller than it), the one closest to the middle of the uncovered part of the screen, between the top bar and the
 * bottom controls. Rows are the list's last items.
 */
internal fun LazyListState.videoToPlay(rows: List<FeedRow>, video: FeedVideo): String? {
    val info = layoutInfo
    val top = 0
    val bottom = info.viewportEndOffset - info.afterContentPadding
    val first = info.totalItemsCount - rows.size
    val middle = (top + bottom) / 2
    return info.visibleItemsInfo
        .mapNotNull { item ->
            val shown = when (val row = rows.getOrNull(item.index - first)) {
                is FeedRow.Photo -> video.shownIn(row.block)
                is FeedRow.StoryPhoto -> row.item
                else -> null
            }?.takeIf { it.type == MediaType.VIDEO } ?: return@mapNotNull null
            val inView = minOf(item.offset + item.size, bottom) - maxOf(item.offset, top)
            if (inView < MIN_SHOWN * minOf(item.size, bottom - top)) return@mapNotNull null
            shown to kotlin.math.abs(item.offset + item.size / 2 - middle)
        }
        .minByOrNull { it.second }
        ?.first?.id
}

private const val MIN_SHOWN = 0.6f

/** A video starts once it has been the one to play this long, so a fling past several doesn't open each of them. */
internal const val VIDEO_SETTLE_MILLIS = 150L

/**
 * [item] playing in a loop with [muted] sound. It draws on a SurfaceView, which the display places on screen by
 * itself: a TextureView made the app redraw (and the GPU sample a 4K picture) for every frame of the video, which
 * cost more than a frame's time at 120 Hz. Its caller keeps the poster on top until [onFirstFrame], and for good after
 * [onFailed]. Muted, it doesn't ask for audio focus, so music from another app keeps playing; with sound it asks for it
 * while it plays, and mutes itself ([onMute]) when another app takes it back.
 */
@Composable
internal fun InlineVideo(
    item: MediaItem,
    muted: Boolean,
    onMute: () -> Unit,
    onFirstFrame: () -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentOnFirstFrame by rememberUpdatedState(onFirstFrame)
    val currentOnFailed by rememberUpdatedState(onFailed)
    val player = remember(item.id) { InlinePlayer(context, item, onFirstFrame = { currentOnFirstFrame() }, onFailed = { currentOnFailed() }) }
    DisposableEffect(player) { onDispose { player.release() } }

    val currentOnMute by rememberUpdatedState(onMute)
    LaunchedEffect(player, muted) { player.setMuted(muted) { currentOnMute() } }

    AndroidExternalSurface(modifier) {
        onSurface { surface, _, _ ->
            player.attach(surface)
            surface.onDestroyed { player.detach() }
        }
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

/**
 * A MediaPlayer drawing on a surface: prepared once the surface is there, then looped. A video only on the server
 * plays with ExoPlayer instead, streamed from its original through the video cache, so what was fetched stays for the
 * next time. Every call to the player runs on [videoThread], never the main thread: opening a file takes 10–18 ms and
 * releasing a player up to 110 ms on a recent phone, which stopped the feed mid-scroll. Its callbacks come back on that
 * thread too and are handed to the main thread.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class InlinePlayer(
    context: Context,
    item: MediaItem,
    private val onFirstFrame: () -> Unit,
    private val onFailed: () -> Unit,
) {
    private val context = context.applicationContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    // Only touched on videoThread.
    private var player: MediaPlayer? = null
    private var stream: ExoPlayer? = null
    private var prepared = false
    private var broken = false

    // Only touched on the main thread.
    private var focus: AudioFocusRequest? = null
    private var released = false

    init {
        videoThread.post {
            if (item.playableUri() == null) {
                openStream(item)
                return@post
            }
            try {
                val created = MediaPlayer()
                player = created
                created.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
                )
                created.setDataSource(this.context, item.playableUri() ?: error("only on the server"))
                created.isLooping = true
                created.setVolume(0f, 0f)
                created.setOnPreparedListener {
                    prepared = true
                    it.start()
                }
                created.setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) main.post(onFirstFrame)
                    false
                }
                // The poster stays; the user can still open the viewer.
                created.setOnErrorListener { _, _, _ ->
                    fail()
                    true
                }
            } catch (e: Exception) {
                fail()
            }
        }
    }

    /** A video only on the server: ExoPlayer on [videoThread], streaming through the video cache. */
    private fun openStream(item: MediaItem) {
        try {
            val playable = ServerVideos.playable(context, item) ?: return fail()
            val created = ExoPlayer.Builder(context)
                .setLooper(videoThread.looper)
                .setMediaSourceFactory(DefaultMediaSourceFactory(playable.dataSource))
                .build()
            stream = created
            // Audio focus is handled here (setMuted), as for the MediaPlayer.
            created.setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                false,
            )
            created.repeatMode = Player.REPEAT_MODE_ONE
            created.volume = 0f
            created.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    main.post(onFirstFrame)
                }

                // The poster stays (offline, say); the user can still open the viewer.
                override fun onPlayerError(error: PlaybackException) = fail()
            })
            created.setMediaItem(playable.item)
        } catch (e: Exception) {
            fail()
        }
    }

    fun setMuted(muted: Boolean, onFocusLost: () -> Unit) {
        if (released) return
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
        videoThread.post {
            if (!broken) {
                runCatching { player?.setVolume(volume, volume) }
                stream?.volume = volume
            }
        }
    }

    fun release() {
        released = true
        abandonFocus()
        videoThread.post {
            player?.release()
            player = null
            stream?.release()
            stream = null
        }
    }

    private fun fail() {
        broken = true
        main.post(onFailed)
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    /** Draws on [surface] (owned by its SurfaceView); the first time, also prepares the player. */
    fun attach(surface: Surface) {
        if (released) return
        videoThread.post {
            if (broken) return@post
            try {
                player?.setSurface(surface)
                stream?.setVideoSurface(surface)
                if (!prepared) {
                    prepared = stream != null
                    player?.prepareAsync()
                    stream?.run {
                        prepare()
                        playWhenReady = true
                    }
                }
            } catch (e: Exception) {
                fail()
            }
        }
    }

    fun detach() {
        videoThread.post {
            if (!broken) {
                runCatching { player?.setSurface(null) }
                stream?.setVideoSurface(null)
            }
        }
    }

    private companion object {
        /** One thread for every inline player, so a player is released before the next one opens its file. */
        val videoThread: Handler by lazy { Handler(HandlerThread("FeedVideo").apply { start() }.looper) }
    }
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
