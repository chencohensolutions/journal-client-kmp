package com.chencohensolution.journal.ui.library

import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.blur
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import android.content.ContentResolver
import android.util.LruCache
import com.chencohensolution.journal.server.ServerPreviews
import androidx.compose.runtime.snapshotFlow
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.chencohensolution.journal.R
import com.chencohensolution.journal.stories.CropKind
import com.chencohensolution.journal.stories.CropRect
import com.chencohensolution.journal.stories.NoteVersion
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.cast.castAhead
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.widthIn

/** Longest side of a photo decoded for the viewer. */
private const val VIEWER_MAX_SIDE = 2560

/**
 * The viewer's full images of the last few photos, by id: a slideshow decodes the next one here before it switches, so
 * the photo shows sharp at once (asked Nov 9, 2026). A few only (each is up to 2560 px, about 20 MB), by bytes.
 */
private val viewerImages = object : LruCache<String, android.graphics.Bitmap>(
    minOf(4L * VIEWER_IMAGE_BYTES, Runtime.getRuntime().maxMemory() / 6).toInt(),
) {
    override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.allocationByteCount
}

/** About what one of [viewerImages] takes: 2560 × 1920 at 4 bytes a pixel. */
private const val VIEWER_IMAGE_BYTES = 2560L * 1920 * 4

/**
 * Each photo fitted to the screen, by id: what the viewer slides between (asked Nov 9, 2026). The photos beside the one
 * shown are made ready here ahead, so each slides in sharp at once; the full image comes after, for the photo shown
 * only. By bytes (a screen-size photo is about 8 MB on a 1080 × 2340 screen).
 */
private val screenPreviews = object : LruCache<String, android.graphics.Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
    override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.allocationByteCount
}

/**
 * [item] fitted inside [screen] (pixels): from [screenPreviews], else decoded at that size (a photo only on the server:
 * the server's preview at that size, no original downloaded) and kept there.
 */
private suspend fun screenPreview(context: android.content.Context, previews: ServerPreviews?, item: MediaItem, screen: IntSize): android.graphics.Bitmap? {
    screenPreviews.get(item.id)?.let { return it }
    // A video too: its poster, made ready ahead so sliding onto it shows it at once, never black (Nov 9, 2026).
    if (screen.width <= 0 || screen.height <= 0) return null
    val ratio = item.ratio
    val (w, h) = if (ratio >= screen.width.toFloat() / screen.height) {
        screen.width to (screen.width / ratio).toInt()
    } else {
        (screen.height * ratio).toInt() to screen.height
    }
    return loadCover(context, previews, item, w.coerceAtLeast(1), h.coerceAtLeast(1))?.also { screenPreviews.put(item.id, it) }
}

/**
 * Room in [screenPreviews] for the photo shown and [ahead] on each side at [screen] size, within a quarter of the app's
 * memory.
 */
private fun fitScreenPreviews(ahead: Int, screen: IntSize) {
    val one = screen.width.toLong() * screen.height * 4
    val wanted = (2L * ahead + 2) * one
    val most = Runtime.getRuntime().maxMemory() / 4
    screenPreviews.resize(wanted.coerceIn(one, most).toInt())
}

/** The steps from the photo shown to make ready, the way the swipes go first: 1, -1, 2, 3, -2, …, about two ahead to one behind. */
internal fun aheadSteps(ahead: Int): List<Int> {
    val forward = (1..ahead).toMutableList()
    val back = (1..ahead).map { -it }.toMutableList()
    val steps = mutableListOf<Int>()
    if (forward.isNotEmpty()) steps += forward.removeAt(0)
    if (back.isNotEmpty()) steps += back.removeAt(0)
    while (forward.isNotEmpty() || back.isNotEmpty()) {
        repeat(2) { if (forward.isNotEmpty()) steps += forward.removeAt(0) }
        if (back.isNotEmpty()) steps += back.removeAt(0)
    }
    return steps
}

/** [item]'s [screenPreview]: from memory at once when made ready ahead, else once loaded. */
@Composable
private fun rememberScreenPreview(item: MediaItem): android.graphics.Bitmap? {
    val context = LocalContext.current
    val previews = LocalServerPreviews.current
    val screen = LocalWindowInfo.current.containerSize
    val preview by produceState(screenPreviews.get(item.id), item.id) {
        if (value == null) value = screenPreview(context, previews, item, screen)
    }
    return preview
}

/** [item]'s full image as the viewer shows it, fitted in [VIEWER_MAX_SIDE]: kept in [viewerImages], else loaded and kept. */
private suspend fun viewerImage(resolver: ContentResolver, previews: ServerPreviews?, item: MediaItem): android.graphics.Bitmap? {
    viewerImages.get(item.id)?.let { return it }
    return withContext(Dispatchers.IO) {
        if (item.hasOriginalHere) loadImage(resolver, previews, item, VIEWER_MAX_SIDE) else serverOriginal(previews, item, VIEWER_MAX_SIDE)
    }?.also { viewerImages.put(item.id, it) }
}

/** Cast: a slideshow waits this long at most for the TV to show a photo before its time starts. */
private const val CAST_PHOTO_WAIT_MILLIS = 10_000L

/** How long the bars and buttons stay after a tap on the photo. */
private const val CHROME_MILLIS = 3_000L

/** How long the zoom's minimap stays after the photo stops moving. */
private const val MINIMAP_MILLIS = 1_500L

/** The same zoom to within a hair (a zoom shown again is worked out anew, so it is rarely exactly equal). */
private fun CropRect?.sameAs(other: CropRect?): Boolean {
    if (this == null || other == null) return this == other
    val e = 0.002f
    return kotlin.math.abs(x - other.x) < e && kotlin.math.abs(y - other.y) < e && kotlin.math.abs(w - other.w) < e && kotlin.math.abs(h - other.h) < e
}

internal class ViewerActions(
    val back: () -> Unit = {},
    /** "Remove" (false) or "Add back" (true) in the details panel's Storyline section. */
    val setInStoryline: (itemId: String, inStoryline: Boolean) -> Unit = { _, _ -> },
    val setWriting: (itemId: String, title: String, text: String) -> Unit = { _, _, _ -> },
    /** Back to an earlier version of the photo's writing. */
    val restoreWriting: (itemId: String, version: NoteVersion) -> Unit = { _, _ -> },
    val toggleTag: (itemId: String, tag: String, add: Boolean) -> Unit = { _, _, _ -> },
    /** The photo now shown, on opening and on each swipe: it is the one that flies back to its tile on closing. */
    val showing: (itemId: String) -> Unit = {},
    /**
     * A swipe came to rest on this photo (not on opening, not in a set): from Moments, a photo of a set opens the set's
     * viewer there, so swiping through Moments always shows sets as sets (asked Nov 10, 2026).
     */
    val settled: (itemId: String) -> Unit = {},
    /**
     * Back to the Moments tab, scrolled to the photo: the day in the top bar, as a chip. Null when the viewer was
     * opened from Moments (closing it lands there already).
     */
    val showInMoments: ((itemId: String) -> Unit)? = null,
    /** Shares the original with another app through the share sheet; false when it can't be had (offline). */
    val share: suspend (MediaItem) -> Boolean = { false },
    /** The map screen, on where the photo was taken (a tap on the details panel's map). */
    val openMap: (GeoPoint) -> Unit = {},
    /** Marks the photo private on the server (true), or no longer private. False when the server couldn't be reached. */
    val setPrivate: suspend (MediaItem, Boolean) -> Boolean = { _, _ -> false },
    /** Leaving a photo: where it was zoomed in (the part on screen; null: fitted), so it opens there next time. */
    val setZoom: (itemId: String, zoom: CropRect?) -> Unit = { _, _ -> },
    /** The details panel opened on a photo: find what the server found in it (people, text, similar photos). */
    val findIn: (MediaItem) -> Unit = {},
    /** In the details panel: a person, a similar photo, all similar photos. */
    val findings: FindingsActions = FindingsActions(),
    /** A face's sheet: This is Mom, Not Mom, Who is it? */
    val faces: FaceActions = FaceActions(),
)

/**
 * The viewer opened on a set ([Route.Viewer.list] a set's): its photos cross-fade instead of sliding, under the photo
 * the scrubber and ☆ Highlight · ▶ Play · Recompose; Write, Details and Share move to the top bar's menu.
 */
internal class ViewerSet(
    /** Its name, when it is a moment made by hand. */
    val name: String? = null,
    /** Its highlights' ids in order; the first is its cover. */
    val highlights: List<String> = emptyList(),
    val setHighlight: (itemId: String, on: Boolean) -> Unit = { _, _ -> },
    val moveHighlight: (itemId: String, to: Int) -> Unit = { _, _ -> },
    /** The crop screen's set mode, on this photo. */
    val recompose: (itemId: String) -> Unit = {},
    /** Names or renames it; blank: unnamed. */
    val rename: (name: String) -> Unit = {},
    /** A swipe past its last ([forward]) or first photo: the photo or set beside it in Moments, if any. */
    val beside: (forward: Boolean) -> Unit = {},
)

/** One photo or video at a time, swiping through [items]. See docs/screens/viewer.md. */
@Composable
internal fun ViewerScreen(
    items: List<MediaItem>,
    startId: String,
    stories: StoriesState,
    actions: ViewerActions,
    /** Opened on a set: its controls, and swipes that cross-fade. */
    set: ViewerSet? = null,
    /** The bars and buttons shown on opening (for the screen previews); a tap shows them otherwise. */
    chromeAtStart: Boolean = false,
    /** What the server found in the photo the details panel shows (it asks with [ViewerActions.findIn]). */
    findings: com.chencohensolution.journal.people.PhotoFindings = com.chencohensolution.journal.people.PhotoFindings.NONE,
    /** The named people, for a face's "Who is it?". */
    people: List<com.chencohensolution.journal.people.Person> = emptyList(),
    /** Photos on each side of the one shown made ready at screen size while swiping (Settings › Advanced). */
    viewerAhead: Int = com.chencohensolution.journal.settings.MomentsPaging.DEFAULT_VIEWER_AHEAD,
    /** Videos play by themselves, muted, and a slideshow plays each to its end; off, they wait for Play. */
    autoplayVideos: Boolean = true,
    setAutoplayVideos: (Boolean) -> Unit = {},
) {
    var faceSheet by remember { mutableStateOf<com.chencohensolution.journal.people.FoundFace?>(null) }
    val onBack = actions.back
    if (items.isEmpty()) {
        // The item was deleted from the device while open.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    HideSystemBars()
    val pager = rememberPagerState(initialPage = items.indexOfFirst { it.id == startId }.coerceAtLeast(0)) { items.size }
    // In a set there is no pager: the photo shown is this, and swipes cross-fade to the next.
    var setIndex by rememberSaveable { mutableIntStateOf(items.indexOfFirst { it.id == startId }.coerceAtLeast(0)) }
    val shownIndex = (if (set != null) setIndex else pager.currentPage).coerceIn(items.indices)
    var playing by rememberSaveable { mutableStateOf(false) }
    // While a slideshow plays, a tap hides its bar (and another brings it back); it plays on (asked Nov 9, 2026).
    var slideBarHidden by remember { mutableStateOf(false) }
    LaunchedEffect(playing) { if (playing) slideBarHidden = false }
    var speed by rememberSaveable { mutableStateOf(SlideSpeed.NORMAL) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }
    // Videos play as soon as they are shown, muted until sound is turned on, and each picks up where it was left while
    // the viewer is open (asked Nov 9, 2026).
    val videoPositions = remember { mutableMapOf<String, Long>() }
    var videoMuted by rememberSaveable { mutableStateOf(true) }
    // The video shown's player, for the controls under the info; and the last video that finished (played to its end, or
    // couldn't play), for the slideshow to go on after it.
    val videoPlayer = remember { mutableStateOf<Player?>(null) }
    val videoDone = remember { mutableStateOf<String?>(null) }
    // Casting to a TV (docs/cast.md): what is shown goes to the TV, videos play there, and the phone shows each photo
    // whole with a frame of what the TV shows, which a pinch or drag moves.
    val cast = JournalCast.controller
    val castState by cast.state.collectAsState()
    val casting = castState.connected
    val castZooms = remember { mutableMapOf<String, CastZoom>() }
    // ▶ Videos is the slideshow's (asked Nov 10, 2026): outside it videos always play by themselves; in it, off, a video
    // shows its poster for a slide's time. Cast to a TV, a slideshow plays every video to its end.
    val inSlideshow = set != null && playing
    val videos = ViewerVideos(
        videoPositions, videoMuted, { videoMuted = !videoMuted }, videoPlayer, videoDone,
        autoplay = !inSlideshow || autoplayVideos, casting = casting, slideshow = inSlideshow,
    )
    val autoplayNow by rememberUpdatedState(autoplayVideos)
    val resolver = LocalContext.current.contentResolver
    val previews = LocalServerPreviews.current
    // The next photo's full image is decoded (downloaded when only on the server) before the slideshow shows it.
    Slideshow(
        set != null && playing, speed.millis, items.size, shownIndex,
        prepare = { i -> items.getOrNull(i)?.takeIf { it.type == MediaType.IMAGE }?.let { viewerImage(resolver, previews, it) } },
        // Cast: a photo's time starts once the TV shows it (a large original takes the TV a while to decode).
        shownBy = { i ->
            val item = items.getOrNull(i)
            if (item != null && item.type != MediaType.VIDEO && cast.state.value.connected) {
                withTimeoutOrNull(CAST_PHOTO_WAIT_MILLIS) {
                    cast.state.first { s -> !s.connected || s.shownId == item.id || s.error?.id == item.id }
                }
            }
        },
        // A video plays to its end (or until it can't play) before the next item: on the TV always, here with ▶ Videos.
        hold = { i ->
            val item = items.getOrNull(i)
            if (item?.type == MediaType.VIDEO) {
                if (cast.state.value.connected) {
                    cast.state.first { s -> !s.connected || s.error?.id == item.id || s.video?.let { it.id == item.id && it.ended } == true }
                } else if (autoplayNow) {
                    snapshotFlow { videoDone.value }.first { it == item.id }
                }
            }
        },
    ) { setIndex = it }
    // Swiping: once a photo is settled on, the photos on each side ([viewerAhead]; Settings › Advanced) are
    // made ready at screen size, the way the swipes go first, so each slides in sharp (asked Nov 9, 2026); a swipe on
    // cancels what was still to come.
    val context = LocalContext.current
    val screenSize = LocalWindowInfo.current.containerSize
    val settledIndex = if (set != null) setIndex else pager.settledPage
    var lastSettled by remember { mutableIntStateOf(settledIndex) }
    LaunchedEffect(settledIndex, items.size, viewerAhead) {
        val way = if (settledIndex < lastSettled) -1 else 1
        lastSettled = settledIndex
        fitScreenPreviews(viewerAhead, screenSize)
        aheadSteps(viewerAhead).forEach { step ->
            items.getOrNull(settledIndex + step * way)?.let { screenPreview(context, previews, it, screenSize) }
        }
    }
    KeepScreenOn(set != null && playing)
    BackHandler(playing) { playing = false }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    // The bars and buttons over the photo: hidden at first, shown by a tap for CHROME_MILLIS, hidden by the next tap.
    // In a set they show from the start (its scrubber is the way through it), until a tap hides them.
    var chrome by remember { mutableStateOf(chromeAtStart || set != null) }
    var choosingTag by rememberSaveable { mutableStateOf(false) }
    var editingCaption by rememberSaveable { mutableStateOf(false) }
    // The whole photo instead of its crop, until turned back (kept while swiping).
    var showWhole by rememberSaveable { mutableStateOf(false) }
    val whole = showWhole
    // A photo's portrait crop shows with the phone upright, its landscape one sideways, fitted to this screen.
    val window = LocalWindowInfo.current.containerSize
    val screenRatio = if (window.width > 0 && window.height > 0) window.width.toFloat() / window.height else 9f / 19.5f
    val cropKind = if (screenRatio <= 1f) CropKind.PORTRAIT else CropKind.LANDSCAPE
    // In a set, its Moments crops (Recompose), so it flips as composed.
    fun cropOf(item: MediaItem) = if (set != null) stories.note(item.id)?.crops?.moments?.takeIf { item.type != MediaType.VIDEO }
    else stories.note(item.id)?.crops?.get(cropKind)?.takeIf { item.type != MediaType.VIDEO }?.fittedTo(screenRatio, item.ratio)
    val current = items[shownIndex]
    // Each photo's zoom, starting where it was left last time (kept in its note) and kept while the viewer is open;
    // zoomed, the whole photo shows instead of its crop.
    val zoomScope = rememberCoroutineScope()
    val zooms = remember { mutableMapOf<String, PhotoZoom>() }
    fun zoomOf(item: MediaItem) = zooms.getOrPut(item.id) { PhotoZoom(zoomScope, stories.note(item.id)?.zoom) }
    val zoom = zoomOf(current)
    val zoomNow by rememberUpdatedState(zoom.takeIf { current.type != MediaType.VIDEO && !casting })
    val currentCrop = cropOf(current)?.takeIf { !zoom.isZoomed && !casting }
    fun castZoomOf(item: MediaItem) = castZooms.getOrPut(item.id) { CastZoom(item.ratio, castState.tvRatio) }
    val castZoom = castZoomOf(current).takeIf { casting && current.type != MediaType.VIDEO }
    val castZoomNow by rememberUpdatedState(castZoom)
    val castZoomed = castZoom?.zoom?.isZoomed == true
    // The TV tells its shape once connected: each photo's frame takes it.
    LaunchedEffect(castState.tvRatio) { castZooms.values.forEach { it.zoom = it.zoom.onTv(castState.tvRatio) } }
    // Each item shown goes to the TV (its original, from this phone's little web server), with the next few fetched
    // there ahead; then the frame follows each pinch and drag.
    val castContext = LocalContext.current
    val castPreviews = LocalServerPreviews.current
    LaunchedEffect(casting, current.id) {
        if (!casting) return@LaunchedEffect
        val server = JournalCast.server() ?: return@LaunchedEffect
        val shownNow = current
        val (item, ahead) = withContext(Dispatchers.IO) {
            server.offer(shownNow, castPreviews) to castAhead(items, items.indexOf(shownNow)).mapNotNull { server.offer(it, castPreviews) }
        }
        if (item == null) {
            Toast.makeText(castContext, R.string.cast_no_wifi, Toast.LENGTH_LONG).show()
            return@LaunchedEffect
        }
        val holder = castZoomOf(shownNow).takeIf { shownNow.type != MediaType.VIDEO }
        cast.show(item, ahead, frame = holder?.zoom?.frame, autoplay = true, positionMillis = videoPositions[shownNow.id] ?: 0L)
        if (holder != null) snapshotFlow { holder.zoom.frame }.collect { cast.frame(shownNow.id, it) }
    }
    LaunchedEffect(castState.error) {
        castState.error?.let { android.util.Log.w("JournalCast", "the TV couldn't show ${it.id}: ${it.message}") }
        if (castState.error != null) {
            Toast.makeText(castContext, R.string.cast_failed, Toast.LENGTH_SHORT).show()
            cast.errorSeen()
        }
    }
    // Closing the viewer leaves the TV on its quiet screen; casting goes on for the next photo opened.
    DisposableEffect(Unit) { onDispose { if (cast.state.value.connected) cast.clear() } }
    // Leaving a photo (a swipe, or closing the viewer): its zoom goes into its note when it changed.
    val latestStories by rememberUpdatedState(stories)
    DisposableEffect(current.id) {
        val left = current
        onDispose {
            if (left.type != MediaType.VIDEO) {
                val part = zoomOf(left).zoomedPart
                if (!part.sameAs(latestStories.note(left.id)?.zoom)) actions.setZoom(left.id, part)
            }
        }
    }
    // A photo zoomed in last time opens as usual (fitted, or at its crop), then glides to that zoom once it has landed:
    // after the viewer's opening transition and once a swipe has settled.
    val opening = if (LocalSharedTransitionScope.current != null) LocalNavAnimatedContentScope.current.transition else null
    LaunchedEffect(current.id) {
        if (current.type == MediaType.VIDEO || !zoom.hasSaved) return@LaunchedEffect
        snapshotFlow { opening?.isRunning != true && !pager.isScrollInProgress }.first { it }
        zoom.resume(cropOf(current)?.takeIf { !showWhole })
    }
    // The minimap shows while the photo moves and a moment after, and with the bars.
    var zoomMoving by remember { mutableStateOf(false) }
    LaunchedEffect(zoom, zoom.scale, zoom.offset) {
        zoomMoving = true
        delay(MINIMAP_MILLIS)
        zoomMoving = false
    }
    LaunchedEffect(current.id) { actions.showing(current.id) }
    val settledNow by rememberUpdatedState(actions.settled)
    LaunchedEffect(set == null) {
        if (set != null) return@LaunchedEffect
        snapshotFlow { if (pager.isScrollInProgress) null else items.getOrNull(pager.settledPage)?.id }
            .filterNotNull().distinctUntilChanged().drop(1)
            .collect { settledNow(it) }
    }
    fun sharedKey(item: MediaItem) = SharedKey.item(item.id)
    // They stay while the details panel, a sheet or the note editor is open, and for the full time again after it closes.
    LaunchedEffect(chrome, showInfo, choosingTag, editingCaption) {
        if (chrome && set == null && !showInfo && !choosingTag && !editingCaption) {
            delay(CHROME_MILLIS)
            chrome = false
        }
    }

    // A set's reel takes its own room (asked Nov 9, 2026): the photo is centred above it in portrait, left of it in
    // landscape. When a tap hides the bars the reel slides away and the photo glides to the middle of the whole screen.
    val setBarsShown = set != null && chrome && !showInfo && !playing
    var reelRoom by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = LocalDensity.current
    val roomBottom by androidx.compose.animation.core.animateDpAsState(
        if (setBarsShown) with(density) { reelRoom.height.toDp() } else 0.dp, tween(BAR_SLIDE_MILLIS), label = "reel room",
    )
    val roomEnd by androidx.compose.animation.core.animateDpAsState(
        if (setBarsShown) with(density) { reelRoom.width.toDp() } else 0.dp, tween(BAR_SLIDE_MILLIS), label = "reel room side",
    )
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    fun roomBottomPx() = with(density) { roomBottom.toPx() }
    fun roomEndPx() = with(density) { roomEnd.toPx() }
    // The swipe's gesture outlives a composition: it calls the latest.
    val besideNow by rememberUpdatedState(set?.beside)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (set != null) Box(
            Modifier
                .fillMaxSize()
                .zoomGestures({ zoomNow }) {
                    chrome = false
                    showInfo = false
                }
                .castGestures({ castZoomNow }, area = { size -> castArea(size, roomBottomPx(), roomEndPx(), rtl) }) {
                    chrome = false
                    showInfo = false
                }
                .pointerInput(current.type == MediaType.VIDEO, playing) {
                    detectTapGestures(
                        onDoubleTap = { at ->
                            if (current.type == MediaType.VIDEO) return@detectTapGestures
                            chrome = false
                            showInfo = false
                            castZoomNow?.let { it.zoom = it.zoom.toggled() }
                            zoomNow?.step(at - Offset(size.width / 2f, size.height / 2f))
                        },
                        onTap = {
                            showInfo = false
                            if (playing) {
                                slideBarHidden = !slideBarHidden
                            } else {
                                chrome = !chrome
                            }
                        },
                    )
                }
                // A swipe cross-fades to the next or previous photo (not while zoomed in: the zoom takes the drag).
                .pointerInput(items.size, rtl) {
                    var dragged = 0f
                    detectHorizontalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = {
                        if (abs(dragged) > SET_SWIPE.toPx()) {
                            val forward = (dragged < 0) != rtl
                            val next = setIndex + if (forward) 1 else -1
                            playing = false
                            if (next in items.indices) setIndex = next else besideNow?.invoke(forward)
                        }
                    }) { change, amount ->
                        if (zoomNow?.isZoomed == true || castZoomNow?.zoom?.isZoomed == true) return@detectHorizontalDragGestures
                        change.consume()
                        dragged += amount
                    }
                },
        ) {
            Crossfade(
                shownIndex, Modifier.fillMaxSize().padding(bottom = roomBottom, end = roomEnd),
                animationSpec = tween(SET_FADE_MILLIS), label = "set photo",
            ) { page ->
                val item = items[page]
                if (item.type == MediaType.VIDEO) {
                    VideoPage(item, sharedKey(item), isCurrent = page == shownIndex, videos)
                } else {
                    val pageZoom = zoomOf(item)
                    Box(Modifier.fillMaxSize().castPlaced(castZoomOf(item).zoom.takeIf { casting })) {
                        PhotoPage(item, sharedKey(item), cropOf(item)?.takeIf { !whole && !pageZoom.isZoomed && !casting }, pageZoom.takeIf { !casting }, shown = page == shownIndex)
                    }
                }
            }
        } else HorizontalPager(
            pager,
            key = { items[it].id },
            // The pages beside the one shown are composed, so their photos load before they slide in.
            beyondViewportPageCount = 1,
            // Zoomed in, a drag moves the photo instead of swiping; double-taps step back to fitted.
            userScrollEnabled = if (casting) !castZoomed else !zoom.isZoomed,
            // A tap on the photo (not a swipe, nor one the play button or the player takes); the details panel and
            // the bars are drawn over it and keep their own taps. Double-taps on a photo step from fitted to filling the
            // screen, to its original size, and back.
            modifier = Modifier
                .fillMaxSize()
                .zoomGestures({ zoomNow }) {
                    chrome = false
                    showInfo = false
                }
                .castGestures({ castZoomNow }, area = { size -> castArea(size, 0f, 0f, rtl) }) {
                    chrome = false
                    showInfo = false
                }
                .pointerInput(current.type == MediaType.VIDEO) {
                    val onTap: (Offset) -> Unit = {
                        showInfo = false
                        chrome = !chrome
                    }
                    if (current.type == MediaType.VIDEO) {
                        detectTapGestures(onTap = onTap)
                    } else {
                        detectTapGestures(
                            onDoubleTap = { at ->
                                chrome = false
                                showInfo = false
                                castZoomNow?.let { it.zoom = it.zoom.toggled() }
                                zoomNow?.step(at - Offset(size.width / 2f, size.height / 2f))
                            },
                            onTap = onTap,
                        )
                    }
                },
        ) { page ->
            val item = items[page]
            if (item.type == MediaType.VIDEO) {
                VideoPage(item, sharedKey(item), isCurrent = page == pager.settledPage, videos)
            } else {
                val pageZoom = zoomOf(item)
                Box(Modifier.fillMaxSize().castPlaced(castZoomOf(item).zoom.takeIf { casting })) {
                    PhotoPage(item, sharedKey(item), cropOf(item)?.takeIf { !whole && !pageZoom.isZoomed && !casting }, pageZoom.takeIf { !casting }, shown = page == pager.settledPage)
                }
            }
        }

        // The TV's screen over the photo shown, in the same room as the photo.
        castZoom?.let { holder ->
            CastFrameOverlay(holder.zoom, Modifier.fillMaxSize().padding(bottom = roomBottom, end = roomEnd))
        }

        AnimatedVisibility(
            // Over a cropped photo with the bars, and hidden with them by a tap (Nov 9, 2026: it was always there).
            chrome && currentCrop != null && !showInfo && !playing,
            Modifier.align(Alignment.TopCenter).statusBarRoom().padding(top = 60.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            CropChip(showingWhole = whole, onToggle = { showWhole = !showWhole })
        }
        if (set != null) {
            run {
                SetBar(
                    items, set, shownIndex, cropOf = { cropOf(it)?.takeIf { !whole } },
                    visible = setBarsShown,
                    onRoom = { reelRoom = it },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    brief = { shown ->
                        PhotoBrief(shown, stories, Modifier.padding(start = 20.dp, end = 8.dp, bottom = 8.dp)) { showInfo = true }
                        // A video's controls: under the info, above the reel (asked Nov 9, 2026).
                        if (shown.type == MediaType.VIDEO && casting) {
                            CastVideoControls(castState.video?.takeIf { it.id == shown.id }, cast::play, cast::pause, cast::seek, Modifier.padding(start = 12.dp, end = 8.dp, bottom = 6.dp))
                        } else if (shown.type == MediaType.VIDEO) videoPlayer.value?.let { VideoControls(it, videoMuted, { videoMuted = !videoMuted }, Modifier.padding(start = 12.dp, end = 8.dp, bottom = 6.dp)) }
                    },
                    onIndex = { setIndex = it }, onPlay = { playing = true }, onWrite = { editingCaption = true },
                    landscape = screenRatio > 1f,
                )
            }
            AnimatedVisibility(playing && !slideBarHidden, Modifier.align(Alignment.BottomCenter).navigationBarRoom(), enter = fadeIn(), exit = fadeOut()) {
                // Cast to a TV, every video plays to its end: no ▶ Videos.
                SlideshowBar(shownIndex, items.size, speed, { speed = it }, autoplayVideos = autoplayVideos.takeUnless { casting }, onAutoplayVideos = setAutoplayVideos, onStop = {
                    playing = false
                    chrome = true
                })
            }
        }
        if (set == null) AnimatedVisibility(
            chrome && !showInfo,
            Modifier.align(Alignment.BottomCenter).navigationBarRoom().padding(bottom = 20.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ProvideTextStyle(OverPhotoText) {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                key(current.id) { PhotoBrief(current, stories, Modifier.padding(start = 20.dp, end = 8.dp, bottom = 12.dp)) { showInfo = true } }
                if (current.type == MediaType.VIDEO && casting) {
                    CastVideoControls(castState.video?.takeIf { it.id == current.id }, cast::play, cast::pause, cast::seek, Modifier.padding(start = 12.dp, end = 8.dp, bottom = 8.dp))
                } else if (current.type == MediaType.VIDEO) videoPlayer.value?.let { VideoControls(it, videoMuted, { videoMuted = !videoMuted }, Modifier.padding(start = 12.dp, end = 8.dp, bottom = 8.dp)) }
                // Icons only, Share in the top bar (asked Nov 10, 2026).
                FloatingBar {
                    BarPill(stringResource(R.string.viewer_write), icon = LibraryIcons.Edit) { editingCaption = true }
                }
            }
            }
        }

        if (current.type != MediaType.VIDEO && !casting) {
            val small = zoom.picture
            val part = if (zoom.isZoomed) zoom.visibleRect(0f) else null
            AnimatedVisibility(
                small != null && part != null && !showInfo && (chrome || zoomMoving),
                Modifier.align(Alignment.TopEnd).statusBarRoom().padding(top = 64.dp, end = 12.dp),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                if (small != null && part != null) ZoomMinimap(small, part, sharp = zoom.atOriginalQuality)
            }
        }

        AnimatedVisibility(chrome && !playing, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            // No gradient behind them (asked Nov 9, 2026): a mild shadow keeps them readable on a bright photo.
            ProvideTextStyle(OverPhotoText) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarRoom()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    ShadowIcon(LibraryIcons.Back, stringResource(R.string.library_back))
                }
                val showInMoments = actions.showInMoments
                if (set != null) {
                    Column(Modifier.weight(1f)) {
                        set.name?.let { Text(it, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                        Text(
                            stringResource(R.string.set_position, shownIndex + 1, items.size) + " · " + dayLabel(current.day),
                            color = PositionColor,
                            fontSize = 13.sp,
                        )
                    }
                    ShareTopButton(current, actions.share)
                    IconButton(onClick = { grid = true }) {
                        ShadowIcon(LibraryIcons.Grid, stringResource(R.string.set_all_photos))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            ShadowIcon(LibraryIcons.More, stringResource(R.string.set_more))
                        }
                        // Write is in the bottom bar and Share in this bar now (asked Nov 10, 2026).
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.library_viewer_details)) }, onClick = {
                                menu = false
                                showInfo = true
                            })
                            showInMoments?.let { show ->
                                DropdownMenuItem(text = { Text(stringResource(R.string.library_show_in_moments)) }, onClick = {
                                    menu = false
                                    show(current.id)
                                })
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(if (set.name != null) R.string.set_rename else R.string.set_name_it)) },
                                onClick = {
                                    menu = false
                                    naming = true
                                },
                            )
                        }
                        if (naming) MomentNameDialog(set.name.orEmpty(), onDismiss = { naming = false }) { name ->
                            naming = false
                            set.rename(name)
                        }
                    }
                } else if (showInMoments == null) {
                    Text(
                        stringResource(R.string.library_viewer_position, pager.currentPage + 1, items.size, dayLabel(current.day)),
                        color = PositionColor,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    // Opened from elsewhere: the day is a chip that shows the photo in Moments.
                    Text(stringResource(R.string.library_viewer_position_count, pager.currentPage + 1, items.size), color = PositionColor, fontSize = 14.sp)
                    MomentsDayChip(dayLabel(current.day), Modifier.padding(start = 6.dp)) { showInMoments(current.id) }
                    Box(Modifier.weight(1f))
                }
                if (set == null) ShareTopButton(current, actions.share)
                CastButton()
            }
            }
        }

        // The Details button is in the bottom bar, under the panel: back or a tap on the photo closes it.
        BackHandler(showInfo) { showInfo = false }
        // Slides up from the bottom edge, and back down when it closes.
        AnimatedVisibility(
            showInfo,
            Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(PANEL_MILLIS)) { it } + fadeIn(tween(PANEL_MILLIS)),
            exit = slideOutVertically(tween(PANEL_MILLIS)) { it } + fadeOut(tween(PANEL_MILLIS)),
        ) {
            LaunchedEffect(current.id) { actions.findIn(current) }
            InfoPanel(
                current,
                findings = findings.takeIf { it.itemId == current.id } ?: com.chencohensolution.journal.people.PhotoFindings.NONE,
                findingsActions = FindingsActions(
                    openPerson = actions.findings.openPerson,
                    openSimilar = actions.findings.openSimilar,
                    moreSimilar = actions.findings.moreSimilar,
                    tapFace = { faceSheet = it },
                ),
                note = stories.note(current.id),
                onAddTag = { choosingTag = true },
                onRemoveTag = { tag -> actions.toggleTag(current.id, tag, false) },
                setInStoryline = { actions.setInStoryline(current.id, it) },
                location = stories.places.location(current)?.let { point -> point to stories.places.name(point) },
                setPrivate = actions.setPrivate,
                openMap = actions.openMap,
            )
        }
    }

    if (grid && set != null) {
        SetGridSheet(
            items,
            set.highlights.mapNotNull { id -> items.firstOrNull { it.id == id } },
            current.id,
            onPick = { item ->
                grid = false
                setIndex = items.indexOf(item).coerceAtLeast(0)
            },
            onMove = { item, to -> set.moveHighlight(item.id, to) },
            onDismiss = { grid = false },
        )
    }
    faceSheet?.let { found ->
        PageSheet({ faceSheet = null }) {
            FaceSheet(found, people, actions.faces, onDone = { faceSheet = null })
        }
    }
    if (choosingTag) {
        TagSheet(
            itemIds = listOf(current.id),
            stories = stories,
            onToggle = { tag, add -> actions.toggleTag(current.id, tag, add) },
            onCreate = { tag -> actions.toggleTag(current.id, tag, true) },
            onDismiss = { choosingTag = false },
        )
    }
    if (editingCaption) {
        NoteEditor(
            note = stories.note(current.id),
            onSave = { title, text ->
                editingCaption = false
                actions.setWriting(current.id, title, text)
            },
            onRestore = { version ->
                editingCaption = false
                actions.restoreWriting(current.id, version)
            },
            onDismiss = { editingCaption = false },
        )
    }
}

/** Under a set's photo: the scrubber, then ☆ Highlight · ▶ Play · Recompose. */
@Composable
private fun SetBar(
    items: List<MediaItem>,
    set: ViewerSet,
    index: Int,
    cropOf: (MediaItem) -> CropRect?,
    brief: @Composable (MediaItem) -> Unit,
    onIndex: (Int) -> Unit,
    onPlay: () -> Unit,
    onWrite: () -> Unit,
    /** Landscape: the reel down the right edge, the info and buttons at the bottom left of it (asked Nov 9, 2026). */
    landscape: Boolean = false,
    /** Shown: the bars slide in (the reel from its edge), hidden they slide out. */
    visible: Boolean = true,
    /** The room the reel takes (pixels): from its top to the bottom edge in portrait, its width in landscape. */
    onRoom: (androidx.compose.ui.unit.IntSize) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val slide = tween<androidx.compose.ui.unit.IntOffset>(BAR_SLIDE_MILLIS)
    // Which way is the end side on screen: +1 right (left to right), -1 left (right to left).
    val endward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    if (landscape) {
        Box(Modifier.fillMaxSize()) {
            if (items.size > 1) {
                AnimatedVisibility(
                    visible, Modifier.align(Alignment.CenterEnd),
                    // Out past its own edge: the right in left-to-right, the left in Hebrew (asked Nov 9, 2026).
                    enter = slideInHorizontally(slide) { it * endward } + fadeIn(), exit = slideOutHorizontally(slide) { it * endward } + fadeOut(),
                ) {
                    Box(
                        Modifier.fillMaxHeight().statusBarRoom().navigationBarRoom()
                            .onSizeChanged { onRoom(androidx.compose.ui.unit.IntSize(it.width, 0)) },
                    ) {
                        SetReel(items, set.highlights.toSet(), index, cropOf, vertical = true, onIndex = onIndex)
                    }
                }
            } else {
                LaunchedEffect(Unit) { onRoom(androidx.compose.ui.unit.IntSize.Zero) }
            }
            AnimatedVisibility(
                visible, Modifier.align(Alignment.BottomStart),
                enter = slideInVertically(slide) { it } + fadeIn(), exit = slideOutVertically(slide) { it } + fadeOut(),
            ) {
                SetBarBottom(
                    items, set, index, brief, onPlay, onWrite, reel = false,
                    cropOf = cropOf, onIndex = onIndex,
                    modifier = Modifier.padding(end = if (items.size > 1) REEL_SIDE_WIDTH + 8.dp else 0.dp),
                )
            }
        }
    } else {
        AnimatedVisibility(
            visible, modifier,
            enter = slideInVertically(slide) { it } + fadeIn(), exit = slideOutVertically(slide) { it } + fadeOut(),
        ) {
            SetBarBottom(items, set, index, brief, onPlay, onWrite, reel = true, cropOf = cropOf, onIndex = onIndex, onRoom = onRoom)
        }
    }
}

/** How long the set's bars and reel take to slide in or out, and the photo to glide to its new middle. */
private const val BAR_SLIDE_MILLIS = 250

/** Under the photo: the info, the reel ([reel]: in portrait), and ☆ Highlight · ▶ Play · ✎ Write · ⌗ Recompose, icons only. */
@Composable
private fun SetBarBottom(
    items: List<MediaItem>,
    set: ViewerSet,
    index: Int,
    brief: @Composable (MediaItem) -> Unit,
    onPlay: () -> Unit,
    onWrite: () -> Unit,
    reel: Boolean,
    cropOf: (MediaItem) -> CropRect?,
    onIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** In portrait, the room from the reel's top to the bottom edge, which the photo keeps clear of. */
    onRoom: (androidx.compose.ui.unit.IntSize) -> Unit = {},
) {
    val item = items[index]
    val place = set.highlights.indexOf(item.id)
    var height by remember { mutableIntStateOf(0) }
    var reelTop by remember { mutableIntStateOf(-1) }
    LaunchedEffect(height, reelTop, reel, items.size) {
        onRoom(androidx.compose.ui.unit.IntSize(0, if (reel && items.size > 1 && reelTop >= 0) height - reelTop else 0))
    }
    Column(
        modifier.fillMaxWidth().navigationBarRoom().padding(top = 24.dp, bottom = 8.dp)
            .onSizeChanged { height = it.height },
    ) {
        // Keyed by the photo, so nothing of the one before is kept while swiping.
        ProvideTextStyle(OverPhotoText) { key(items[index].id) { brief(items[index]) } }
        // The actions in a floating bar as the Moments feed has (asked Nov 9, 2026), above the reel.
        FloatingBar(Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 10.dp)) {
            BarPill(
                stringResource(if (place >= 0) R.string.set_highlighted else R.string.set_highlight),
                glyph = if (place >= 0) "★" else "☆",
            ) { set.setHighlight(item.id, place < 0) }
            BarPill(stringResource(R.string.set_play), icon = LibraryIcons.Play, selected = true, onClick = onPlay)
            BarPill(stringResource(R.string.viewer_write), icon = LibraryIcons.Edit, onClick = onWrite)
            BarPill(stringResource(R.string.set_recompose), glyph = "⌗", enabled = item.type != MediaType.VIDEO) { set.recompose(item.id) }
        }
        // The reel last, along the bottom edge (asked Nov 9, 2026); the photo keeps clear of it.
        if (reel && items.size > 1) {
            Box(Modifier.onPlaced { reelTop = it.positionInParent().y.toInt() }) {
                SetReel(items, set.highlights.toSet(), index, cropOf, onIndex = onIndex)
            }
        }
    }
}

/** The viewer's actions in a bar floating over the photo, as the Moments feed's tabs (`SegmentedBar`): same shape and shadow. */
@Composable
private fun FloatingBar(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier
            .shadow(12.dp, RoundedCornerShape(50))
            .background(LibraryColors.Raised.copy(alpha = 0.95f), RoundedCornerShape(50))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * One action in the [FloatingBar], looking as the feed's tabs: [selected] is the filled one (Play). An icon only (asked
 * Nov 10, 2026); [label] is what it says to TalkBack.
 */
@Composable
private fun BarPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    glyph: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = if (selected) LibraryColors.OnInverse else LibraryColors.OnBackground
    Box(
        Modifier
            .height(40.dp)
            .widthIn(min = 52.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) LibraryColors.Inverse else Color.Transparent)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 14.dp)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        when {
            icon != null -> Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            glyph != null -> Text(glyph, color = color, fontSize = 19.sp, style = androidx.compose.ui.text.TextStyle.Default)
        }
    }
}

/** Share in the top bar (asked Nov 10, 2026): the original to another app, a spinner while it is fetched. */
@Composable
private fun ShareTopButton(item: MediaItem, share: suspend (MediaItem) -> Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember(item.id) { mutableStateOf(false) }
    IconButton(enabled = !sharing, onClick = {
        sharing = true
        scope.launch {
            if (!share(item)) Toast.makeText(context, R.string.library_share_failed, Toast.LENGTH_LONG).show()
            sharing = false
        }
    }) {
        if (sharing) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        else ShadowIcon(LibraryIcons.Share, stringResource(R.string.library_share))
    }
}

/** Text over the photo without a gradient behind it: a mild shadow keeps it readable on a bright photo (Nov 9, 2026). */
private val OverPhotoText = androidx.compose.ui.text.TextStyle(
    shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 1f), blurRadius = 8f),
)

/** A white icon over the photo with a mild shadow under it, as [OverPhotoText]. */
@Composable
private fun ShadowIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String?, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 24.dp) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.Black.copy(alpha = 0.8f), modifier = Modifier.matchParentSize().offset(y = 1.dp).blur(3.dp))
        Icon(icon, description, tint = Color.White, modifier = Modifier.matchParentSize())
    }
}

@Composable
private fun SetBarButton(glyph: String, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp).alpha(if (enabled) 1f else 0.4f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(glyph, color = Color.White, fontSize = 17.sp, lineHeight = 18.sp)
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 13.sp)
    }
}

/**
 * A photo: at screen size at once when made ready ahead ([screenPreviews]), and, once it is the one [shown], its full
 * image over it. [zoom] is the viewer's for the photo.
 */
@Composable
private fun PhotoPage(item: MediaItem, sharedKey: String, crop: CropRect?, zoom: PhotoZoom?, shown: Boolean = true) {
    if (!item.hasOriginalHere) return ServerPhotoPage(item, sharedKey, crop, zoom, shown)
    val resolver = LocalContext.current.contentResolver
    val previews = LocalServerPreviews.current
    val preview = rememberThumbnail(item, THUMB_LARGE)
    val screen = rememberScreenPreview(item)
    // Already decoded when a slideshow made it ready: sharp from the first frame. Else loaded once shown.
    var full by remember(item.id) { mutableStateOf(viewerImages.get(item.id)) }
    LaunchedEffect(item.id, shown) { if (shown && full == null) full = viewerImage(resolver, previews, item) }
    val sharp = rememberZoomedOriginal(item, zoom) { loadImage(resolver, previews, item, zoomDecodeSide(item)) }
    val image = sharp ?: (full ?: screen)?.asImageBitmap() ?: preview
    val small = preview ?: remember(full) { full?.asImageBitmap() }
    SideEffect { zoom?.picture = small }
    val detail = rememberDetailLayer(item, zoom, shownSide = image?.let { maxOf(it.width, it.height) } ?: 0) {
        openOriginalRegions(resolver, previews, item)
    }
    ReportQuality(zoom, item, sharp = sharp != null, shownSide = image?.let { maxOf(it.width, it.height) } ?: 0, detail = detail)
    if (image != null) ViewerImage(image, sharedKey, crop, zoom = zoom, pixelSide = maxOf(item.width, item.height), detail = detail)
}

/**
 * The original at its own size (or as large as can be drawn, [zoomDecodeSide]) once [zoom] first zooms in, kept while
 * the photo stays on screen; null until then.
 */
@Composable
private fun rememberZoomedOriginal(item: MediaItem, zoom: PhotoZoom?, load: suspend () -> android.graphics.Bitmap?): ImageBitmap? {
    val zoomed = zoom?.isZoomed == true
    var wanted by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(zoomed) { if (zoomed) wanted = true }
    val original by produceState<ImageBitmap?>(null, item.id, wanted) {
        if (wanted) value = withContext(Dispatchers.IO) { load()?.asImageBitmap() }
    }
    return original
}

/**
 * A photo only on the server: the best image of it already on this phone shows at once (asking the server for nothing),
 * while its original downloads into the cache (kept there); the original then fades in over it. A small spinner shows
 * while it downloads. When this phone can't decode the original (Samsung's DNG 1.7), a preview at screen size is asked
 * for instead; offline, the cached image stays.
 */
@Composable
private fun ServerPhotoPage(item: MediaItem, sharedKey: String, crop: CropRect?, zoom: PhotoZoom?, shown: Boolean = true) {
    val previews = LocalServerPreviews.current
    val resolver = LocalContext.current.contentResolver
    // The server's preview at screen size (made ready ahead while swiping), else the best image already on this phone.
    val screen = rememberScreenPreview(item)
    val kept by produceState<android.graphics.Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { cachedServerImage(previews, item) }
    }
    val cached = screen ?: kept
    // The spinner is for a download only: an original already in the cache is just decoded, with nothing to wait for.
    var downloading by remember(item.id) { mutableStateOf(false) }
    // Already decoded when a slideshow made it ready: sharp from the first frame. Else downloaded once shown.
    var original by remember(item.id) { mutableStateOf(viewerImages.get(item.id)) }
    LaunchedEffect(item.id, shown) {
        if (!shown || original != null) return@LaunchedEffect
        if (item.sha256?.let { previews?.cachedOriginal(it) } == null) downloading = true
        try {
            original = viewerImage(resolver, previews, item)
        } finally {
            downloading = false
        }
    }
    // Zoomed in: decoded again from the downloaded original, at its own size.
    val sharp = rememberZoomedOriginal(item, zoom) { serverOriginal(previews, item, zoomDecodeSide(item)) }
    val drawn = sharp ?: (cached ?: original)?.asImageBitmap()
    val detail = rememberDetailLayer(item, zoom, shownSide = drawn?.let { maxOf(it.width, it.height) } ?: 0) {
        openOriginalRegions(resolver, previews, item)
    }
    ReportQuality(zoom, item, sharp = sharp != null, shownSide = drawn?.let { maxOf(it.width, it.height) } ?: 0, detail = detail)
    val progress by (previews?.originalProgress ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, Float>()) }).collectAsState()
    val fade by animateFloatAsState(if (original != null) 1f else 0f, tween(ORIGINAL_FADE_MILLIS), label = "original")
    // The minimap's picture: what this phone had of it, else the original as first decoded.
    val small = remember(cached, original) { (cached ?: original)?.asImageBitmap() }
    SideEffect { zoom?.picture = small }
    Box(Modifier.fillMaxSize()) {
        val base = cached ?: original
        val pixelSide = maxOf(item.width, item.height)
        if (sharp != null) {
            ViewerImage(sharp, sharedKey, crop, zoom = zoom, pixelSide = pixelSide, detail = detail)
        } else if (base != null) {
            ViewerImage(
                base.asImageBitmap(), sharedKey, crop,
                over = original?.takeIf { cached != null }?.asImageBitmap(), overAlpha = fade,
                zoom = zoom, pixelSide = pixelSide,
            )
        }
        // A cloud filling as the original downloads; shown only after a moment, so a quick one never flashes.
        var showCloud by remember(item.id) { mutableStateOf(false) }
        LaunchedEffect(downloading) {
            if (downloading) delay(CLOUD_DELAY_MILLIS)
            showCloud = downloading
        }
        AnimatedVisibility(
            showCloud,
            Modifier.align(Alignment.TopStart).statusBarRoom().padding(top = 64.dp, start = 14.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            DownloadCloud(item.sha256?.let { progress[it] } ?: 0f)
        }
    }
}

private const val ORIGINAL_FADE_MILLIS = 250

/** How long a download goes before its cloud shows. */
private const val CLOUD_DELAY_MILLIS = 300L

/** The cloud of [LibraryIcons.CloudUp], without its arrow. */
private val CLOUD_PATH = PathParser().parsePathString("M17.5 19H7a5 5 0 1 1 1.1-9.9A6 6 0 0 1 19.5 11a4 4 0 0 1-2 8z").toPath()

/** The original coming from the server: a cloud outline filling from the bottom as [progress] (0..1) goes. */
@Composable
private fun DownloadCloud(progress: Float) {
    val level by animateFloatAsState(progress, tween(200), label = "download")
    Canvas(
        Modifier
            .size(34.dp)
            .background(LibraryColors.PillScrim, RoundedCornerShape(50))
            .padding(7.dp),
    ) {
        val bounds = CLOUD_PATH.getBounds()
        val scale = size.width / 24f
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            // Filled up to the share downloaded, measured on the cloud itself, not on its 24-unit box.
            val top = bounds.bottom - (bounds.bottom - bounds.top) * level
            clipRect(top = top) { drawPath(CLOUD_PATH, Color.White) }
            drawPath(CLOUD_PATH, Color.White, style = Stroke(width = 2f, join = StrokeJoin.Round))
        }
    }
}


/**
 * Immersive while the viewer is open: the status bar and the gesture bar are hidden (a swipe from the edge shows them
 * for a moment), and shown again when it closes.
 */
@Composable
private fun HideSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/**
 * The photo at its own shape (or its [crop]'s), centred: it looks fitted, and it is shared with the tile it was opened
 * from, so its crop opens up as the tile grows into it. [zoom] scales and moves it; [pixelSide] is the longer side of
 * the original's pixels, which "Original size" shows one to one (0: the image's own); [detail] the parts of the original
 * decoded at full detail over it, once zoomed in past what [image] has.
 */
@Composable
private fun ViewerImage(
    image: ImageBitmap,
    sharedKey: String,
    crop: CropRect? = null,
    over: ImageBitmap? = null,
    overAlpha: Float = 1f,
    zoom: PhotoZoom? = null,
    pixelSide: Int = 0,
    detail: DetailLayer? = null,
) {
    val ratio = image.width.toFloat() / image.height
    // [over] (the original, once downloaded) is drawn on top of [image] in the same place, fading in.
    @Composable
    fun Pictures(scale: ContentScale) {
        Image(image, contentDescription = null, contentScale = scale, modifier = Modifier.fillMaxSize())
        over?.let { Image(it, contentDescription = null, contentScale = scale, modifier = Modifier.fillMaxSize().alpha(overAlpha)) }
    }
    val side = pixelSide.takeIf { it > 0 } ?: maxOf(image.width, image.height)
    // Told again on every change: after a swipe the zoom is a new one, while the screen's size stays the same.
    var screen by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    SideEffect { if (!screen.isEmpty()) zoom?.layout(screen, ratio, side) }
    // The part shown: the crop, or the whole photo. Turning the crop off or on (the chip) glides between them, the same
    // image opening out or closing in (asked Nov 9, 2026); a zoom, which drops the crop too, takes over at once.
    val target = crop ?: WHOLE_PHOTO
    val frame = remember { Animatable(target, CropRectConverter) }
    val zoomed = zoom?.isZoomed == true
    LaunchedEffect(target.x, target.y, target.w, target.h) {
        if (zoomed) frame.snapTo(target) else frame.animateTo(target, tween(CROP_GLIDE_MILLIS, easing = FastOutSlowInEasing))
    }
    val shown = frame.value
    val gliding = !zoomed && !shown.samePart(target)
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { screen = it.toSize() },
        contentAlignment = Alignment.Center,
    ) {
        if (crop != null || gliding) {
            CropBox(shown, Modifier.aspectRatio(ratio * shown.w / shown.h).shared(sharedKey)) { Pictures(ContentScale.FillBounds) }
        } else {
            // Laid out at its zoomed size and place (not only drawn so), so closing flies to the tile from the zoomed
            // view rather than from the fitted photo.
            val placed = if (zoom != null) Modifier.zoomedLayout(zoom, ratio) else Modifier.aspectRatio(ratio)
            Box(placed.shared(sharedKey).detailOver(detail)) { Pictures(ContentScale.Crop) }
        }
    }
}

/** The whole photo as a crop, for gliding to and from one. */
private val WHOLE_PHOTO = CropRect(0f, 0f, 1f, 1f)

/** How long turning a crop off or on takes. */
private const val CROP_GLIDE_MILLIS = 300

private fun CropRect.samePart(other: CropRect) = x == other.x && y == other.y && w == other.w && h == other.h

/** A crop's part as four numbers, for animating it (its shape on another screen is not animated). */
private val CropRectConverter = androidx.compose.animation.core.TwoWayConverter<CropRect, androidx.compose.animation.core.AnimationVector4D>(
    convertToVector = { androidx.compose.animation.core.AnimationVector4D(it.x, it.y, it.w, it.h) },
    convertFromVector = { CropRect(it.v1, it.v2, it.v3, it.v4) },
)

/** "Cropped · Show all" over a cropped photo, or "Show crop" while the whole photo shows. */
@Composable
private fun CropChip(showingWhole: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(LibraryColors.PillScrim, RoundedCornerShape(50))
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LibraryIcons.Crop, null, tint = Color.White, modifier = Modifier.size(14.dp))
        if (!showingWhole) Text(stringResource(R.string.viewer_cropped) + " ·", color = Color(0xFFB8C2BF), fontSize = 13.sp)
        Text(
            stringResource(if (showingWhole) R.string.viewer_show_crop else R.string.viewer_show_all),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A round-ended button over the photo: Write, Crop. */
/** How long the details panel takes to slide in or out. */
private const val PANEL_MILLIS = 250

/** The top bar's position and day ("4 of 29 · Yesterday"). */
private val PositionColor = Color(0xFFB8C2BF)

/** The top bar's day as a chip ("Thu, Oct 8 ›"): a tap shows the photo in Moments. */
@Composable
private fun MomentsDayChip(day: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val label = stringResource(R.string.library_show_in_moments)
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = "$label, $day" }
            .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(day, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Icon(LibraryIcons.ChevronRight, null, tint = Color.White, modifier = Modifier.padding(start = 2.dp).size(14.dp))
    }
}

/** A round Share button after the pills; a spinner while an original only on the server is fetched to share. */
@Composable
private fun ShareButton(item: MediaItem, share: suspend (MediaItem) -> Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember(item.id) { mutableStateOf(false) }
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(LibraryColors.PillScrim)
            .clickable(enabled = !sharing, onClickLabel = stringResource(R.string.library_share)) {
                sharing = true
                scope.launch {
                    if (!share(item)) Toast.makeText(context, R.string.library_share_failed, Toast.LENGTH_LONG).show()
                    sharing = false
                }
            }
            .semantics { contentDescription = context.getString(R.string.library_share) },
        contentAlignment = Alignment.Center,
    ) {
        if (sharing) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else {
            Icon(LibraryIcons.Share, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ViewerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .height(44.dp)
            .background(LibraryColors.PillScrim, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** How long a video's poster takes to fade out over its first frame. */
private const val POSTER_FADE_MILLIS = 180

/**
 * The viewer's videos: where each was left (by id, while the viewer is open), whether they play muted, the [player] of
 * the one shown (for its controls under the info) and the last one [done] (for the slideshow).
 */
private class ViewerVideos(
    val positions: MutableMap<String, Long>,
    val muted: Boolean,
    val toggleMute: () -> Unit,
    val player: androidx.compose.runtime.MutableState<Player?>,
    val done: androidx.compose.runtime.MutableState<String?>,
    /** Whether they play by themselves; off, each waits for Play. */
    val autoplay: Boolean = true,
    /** Casting: videos play on the TV, and the phone shows their poster. */
    val casting: Boolean = false,
    /** A slideshow plays: a video that doesn't play by itself shows only its poster, no Play over it. */
    val slideshow: Boolean = false,
)

/**
 * A video plays as soon as it is the one shown, muted until sound is turned on ([ViewerVideos.muted]), over its poster
 * until its first frame; swiped away it stops, and back on it later it goes on from where it was. A video only on the
 * server streams from its original, and what was fetched stays cached ([ServerVideos]).
 */
@Composable
private fun VideoPage(item: MediaItem, sharedKey: String, isCurrent: Boolean, videos: ViewerVideos) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // A video only on the server shows the poster already kept (the feed's or a grid's), asking for no new size.
        // Made ready ahead at screen size like the photos beside it, so it shows the moment it is slid onto.
        val ready = rememberScreenPreview(item)?.asImageBitmap()
        val kept = if (item.hasOriginalHere) rememberThumbnail(item, THUMB_LARGE) else rememberCachedServerImage(item)
        val poster = ready ?: kept
        // Its first frame drawn by the player: until then the poster stays over it (the player's surface is black until
        // a frame), so sliding onto a video never flashes black (Nov 9, 2026).
        var rendered by remember(item.id) { mutableStateOf(false) }
        LaunchedEffect(isCurrent) { if (!isCurrent) rendered = false }
        // Off by itself: Play over the poster starts it.
        var started by remember(item.id) { mutableStateOf(false) }
        LaunchedEffect(isCurrent) { if (!isCurrent) started = false }
        if (isCurrent && !videos.casting && (videos.autoplay || started)) {
            // Shown again: not done any more, so a slideshow waits for it again.
            LaunchedEffect(item.id) { if (videos.done.value == item.id) videos.done.value = null }
            VideoPlayer(
                item, startAt = videos.positions[item.id] ?: 0L, muted = videos.muted,
                // Only the player that has the controls takes them back, so a video swiped to keeps its own.
                onPlayer = { player, on -> if (on) videos.player.value = player else if (videos.player.value === player) videos.player.value = null },
                onDone = { videos.done.value = item.id },
                onStopped = { videos.positions[item.id] = it },
                onFirstFrame = { rendered = true },
            )
        }
        // Decided in the same frame the player goes, so the poster is back before its surface is: no black frame. Once the
        // first frame is drawn, the poster fades out over it rather than being cut away (a start reads as one move).
        val covering = !(isCurrent && rendered)
        val fading by animateFloatAsState(if (covering) 1f else 0f, if (covering) snap() else tween(POSTER_FADE_MILLIS), label = "poster")
        // Covering again is at once, in this frame; only the fade out over the first frame is animated.
        val posterAlpha = if (covering) 1f else fading
        if (posterAlpha > 0f) poster?.let { Box(Modifier.fillMaxSize().alpha(posterAlpha), contentAlignment = Alignment.Center) { ViewerImage(it, sharedKey) } }
        if (isCurrent && !videos.casting && !videos.slideshow && !videos.autoplay && !started) {
            IconButton(
                onClick = { started = true },
                colors = IconButtonDefaults.iconButtonColors(containerColor = LibraryColors.BadgeScrim),
                modifier = Modifier.size(72.dp),
            ) {
                Icon(LibraryIcons.Play, stringResource(R.string.library_viewer_play), tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}

/**
 * ExoPlayer playing [item] from [startAt] (ms), its controls drawn by the viewer under the info ([VideoControls], given
 * the player through [onPlayer]). Released when it leaves the screen, which stops a stream from downloading further;
 * [onStopped] gets where it was. [onDone] once it played to its end, or when it can't play.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(
    item: MediaItem,
    startAt: Long,
    muted: Boolean,
    onPlayer: (Player, Boolean) -> Unit,
    onDone: () -> Unit,
    onStopped: (Long) -> Unit,
    onFirstFrame: () -> Unit = {},
) {
    val done by rememberUpdatedState(onDone)
    val firstFrame by rememberUpdatedState(onFirstFrame)
    val handOver by rememberUpdatedState(onPlayer)
    val context = LocalContext.current
    var player by remember(item.id) { mutableStateOf<ExoPlayer?>(null) }
    var failed by remember(item.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(item.id) {
        val playable = withContext(Dispatchers.IO) { runCatching { ServerVideos.playable(context, item) }.getOrNull() }
        if (playable == null) {
            failed = R.string.library_viewer_video_unavailable
            done()
            return@LaunchedEffect
        }
        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(playable.dataSource))
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        failed = R.string.library_viewer_video_failed
                        done()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) done()
                    }

                    override fun onRenderedFirstFrame() = firstFrame()
                })
                volume = if (muted) 0f else 1f
                setMediaItem(playable.item, startAt)
                prepare()
                playWhenReady = true
            }
    }
    LaunchedEffect(player, muted) { player?.volume = if (muted) 0f else 1f }
    LaunchedEffect(player) { player?.let { handOver(it, true) } }
    val stopped by rememberUpdatedState(onStopped)
    DisposableEffect(item.id) {
        onDispose {
            player?.let {
                // Played to its end: next time it starts over.
                stopped(if (it.playbackState == Player.STATE_ENDED) 0L else it.currentPosition)
                it.release()
            }
            player?.let { handOver(it, false) }
            player = null
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            // Clear until the first frame, so the poster under it shows meanwhile instead of black.
            // A TextureView, no controls of its own, clear until the first frame (res/layout/viewer_player.xml): its controls
            // are drawn under the info, and the poster over it shows until its first frame without a black flash.
            factory = { android.view.LayoutInflater.from(it).inflate(R.layout.viewer_player, null) as PlayerView },
            update = { it.player = player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        failed?.let { Text(stringResource(it), color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(24.dp)) }
    }
}

/**
 * The video shown's controls, under its info (asked Nov 9, 2026: the player's own sat behind the set's reel): play or
 * pause, where it is with a bar to move it, how long it is, and sound on or off.
 */
@Composable
private fun VideoControls(player: Player, muted: Boolean, onToggleMute: () -> Unit, modifier: Modifier = Modifier) {
    var position by remember(player) { mutableLongStateOf(player.currentPosition) }
    var duration by remember(player) { mutableLongStateOf(player.duration.coerceAtLeast(0L)) }
    var playingNow by remember(player) { mutableStateOf(player.isPlaying) }
    var dragging by remember(player) { mutableStateOf<Float?>(null) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            playingNow = player.isPlaying
            delay(VIDEO_CONTROLS_TICK_MILLIS)
        }
    }
    // One slim row, about 32 dp (asked Nov 9, 2026: the bar and the times stood too tall).
    Row(modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallControl(
            if (playingNow) AudioIcons.Pause else LibraryIcons.Play,
            stringResource(if (playingNow) R.string.library_viewer_pause else R.string.library_viewer_play),
        ) {
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            }
            playingNow = player.isPlaying
        }
        Text(formatClock(dragging?.toLong() ?: position), color = Color.White, fontSize = 11.sp)
        val length = duration.toFloat().coerceAtLeast(1f)
        SlimSeekBar(
            fraction = ((dragging ?: position.toFloat()) / length).coerceIn(0f, 1f),
            onDrag = { dragging = it * length },
            onDone = {
                dragging?.let { player.seekTo(it.toLong()) }
                dragging = null
            },
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        Text(formatClock(duration), color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
        // Sound, with the rest of the video's controls (asked Nov 9, 2026: it floated apart from them).
        SmallControl(
            if (muted) SoundIcons.Off else SoundIcons.On,
            stringResource(if (muted) R.string.feed_unmute else R.string.feed_mute),
            Modifier.padding(start = 4.dp),
            onClick = onToggleMute,
        )
    }
}

/** A small white icon button of the video controls (32 dp, the icon 18 dp). */
@Composable
private fun SmallControl(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(32.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        ShadowIcon(icon, label, size = 18.dp)
    }
}

/**
 * A thin seek bar: a 3 dp line, the part played white, a 10 dp dot where it is. The whole row's height takes the
 * finger: a tap or a drag moves along it ([onDrag], 0 to 1), letting go seeks ([onDone]).
 */
@Composable
private fun SlimSeekBar(fraction: Float, onDrag: (Float) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val drag by rememberUpdatedState(onDrag)
    val done by rememberUpdatedState(onDone)
    androidx.compose.foundation.Canvas(
        modifier.fillMaxHeight().pointerInput(rtl) {
            fun at(x: Float) = ((if (rtl) size.width - x else x) / size.width).coerceIn(0f, 1f)
            awaitEachGesture {
                val down = awaitFirstDown()
                drag(at(down.position.x))
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    drag(at(change.position.x))
                    change.consume()
                }
                done()
            }
        },
    ) {
        val line = 3.dp.toPx()
        val y = size.height / 2
        val x = size.width * (if (rtl) 1f - fraction else fraction)
        drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y), strokeWidth = line, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(Color.White, Offset(if (rtl) size.width else 0f, y), Offset(x, y), strokeWidth = line, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(x, y))
    }
}

/** How often the video controls read where the video is. */
private const val VIDEO_CONTROLS_TICK_MILLIS = 250L

/** "1:05", "12:40", "1:02:03". */
private fun formatClock(millis: Long): String {
    val seconds = millis / 1000
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** A moment's name, to give or change (the set viewer's ⋯ menu); saved as typed, an empty one leaves it unnamed. */
@Composable
private fun MomentNameDialog(name: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var typed by rememberSaveable { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_name_title)) },
        text = {
            OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.set_name_hint)) }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onSave(typed) }) { Text(stringResource(R.string.set_name_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) } },
    )
}

/**
 * A photo's brief info over it, with the bars (asked Nov 9, 2026): where it was taken, its date and time, its tags,
 * and ⓘ for the details panel.
 */
@Composable
private fun PhotoBrief(item: MediaItem, stories: StoriesState, modifier: Modifier = Modifier, onInfo: () -> Unit) {
    val place = stories.places.location(item)?.let { stories.places.name(it) }
        ?.let { name -> listOfNotNull(name.label, name.city, name.country).distinct().joinToString(", ") }?.takeIf { it.isNotEmpty() }
    val taken = java.time.Instant.ofEpochMilli(item.takenAtMillis).atZone(java.time.ZoneId.systemDefault())
    val tags = stories.note(item.id)?.tags.orEmpty()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            place?.let { Text(it, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(formatDate(taken, "EEEdMMMyyyyjmmss"), color = Color.White, fontSize = 13.sp, maxLines = 1)
            if (tags.isNotEmpty()) {
                Text(tags.joinToString("  ") { "#$it" }, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onInfo) {
            ShadowIcon(LibraryIcons.Info, stringResource(R.string.library_viewer_details))
        }
    }
}
