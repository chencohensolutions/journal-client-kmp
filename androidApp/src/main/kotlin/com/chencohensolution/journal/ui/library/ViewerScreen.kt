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
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.chencohensolution.journal.R
import com.chencohensolution.journal.stories.CropKind
import com.chencohensolution.journal.stories.CropRect
import com.chencohensolution.journal.stories.NoteVersion
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Longest side of a photo decoded for the viewer. */
private const val VIEWER_MAX_SIDE = 2560

/**
 * The viewer's full images of the last few photos, by id: a slideshow decodes the next one here before it switches,
 * so the photo shows sharp at once (asked Nov 9, 2026). A few only: each is up to 2560 px, about 20 MB.
 */
private val viewerImages = LruCache<String, android.graphics.Bitmap>(4)

/** [item]'s full image as the viewer shows it, fitted in [VIEWER_MAX_SIDE]: kept in [viewerImages], else loaded and kept. */
private suspend fun viewerImage(resolver: ContentResolver, previews: ServerPreviews?, item: MediaItem): android.graphics.Bitmap? {
    viewerImages.get(item.id)?.let { return it }
    return withContext(Dispatchers.IO) {
        if (item.hasOriginalHere) loadImage(resolver, previews, item, VIEWER_MAX_SIDE) else serverOriginal(previews, item, VIEWER_MAX_SIDE)
    }?.also { viewerImages.put(item.id, it) }
}

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
    var speed by rememberSaveable { mutableStateOf(SlideSpeed.NORMAL) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }
    val resolver = LocalContext.current.contentResolver
    val previews = LocalServerPreviews.current
    // The next photo's full image is decoded (downloaded when only on the server) before the slideshow shows it.
    Slideshow(set != null && playing, speed.millis, items.size, shownIndex, prepare = { i ->
        items.getOrNull(i)?.takeIf { it.type == MediaType.IMAGE }?.let { viewerImage(resolver, previews, it) }
    }) { setIndex = it }
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
    val zoomNow by rememberUpdatedState(zoom.takeIf { current.type != MediaType.VIDEO })
    val currentCrop = cropOf(current)?.takeIf { !zoom.isZoomed }
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
    fun sharedKey(item: MediaItem) = SharedKey.item(item.id)
    // They stay while the details panel, a sheet or the note editor is open, and for the full time again after it closes.
    LaunchedEffect(chrome, showInfo, choosingTag, editingCaption) {
        if (chrome && set == null && !showInfo && !choosingTag && !editingCaption) {
            delay(CHROME_MILLIS)
            chrome = false
        }
    }

    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
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
                .pointerInput(current.type == MediaType.VIDEO, playing) {
                    detectTapGestures(
                        onDoubleTap = { at ->
                            if (current.type == MediaType.VIDEO) return@detectTapGestures
                            chrome = false
                            showInfo = false
                            zoomNow?.step(at - Offset(size.width / 2f, size.height / 2f))
                        },
                        onTap = {
                            showInfo = false
                            if (playing) {
                                playing = false
                                chrome = true
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
                        if (zoomNow?.isZoomed == true) return@detectHorizontalDragGestures
                        change.consume()
                        dragged += amount
                    }
                },
        ) {
            Crossfade(shownIndex, Modifier.fillMaxSize(), animationSpec = tween(SET_FADE_MILLIS), label = "set photo") { page ->
                val item = items[page]
                if (item.type == MediaType.VIDEO) {
                    VideoPage(item, sharedKey(item), isCurrent = page == shownIndex)
                } else {
                    val pageZoom = zoomOf(item)
                    PhotoPage(item, sharedKey(item), cropOf(item)?.takeIf { !whole && !pageZoom.isZoomed }, pageZoom)
                }
            }
        } else HorizontalPager(
            pager,
            key = { items[it].id },
            // Zoomed in, a drag moves the photo instead of swiping; double-taps step back to fitted.
            userScrollEnabled = !zoom.isZoomed,
            // A tap on the photo (not a swipe, nor one the play button or the player takes); the details panel and
            // the bars are drawn over it and keep their own taps. Double-taps on a photo step from fitted to filling the
            // screen, to its original size, and back.
            modifier = Modifier
                .fillMaxSize()
                .zoomGestures({ zoomNow }) {
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
                                zoomNow?.step(at - Offset(size.width / 2f, size.height / 2f))
                            },
                            onTap = onTap,
                        )
                    }
                },
        ) { page ->
            val item = items[page]
            if (item.type == MediaType.VIDEO) {
                VideoPage(item, sharedKey(item), isCurrent = page == pager.currentPage)
            } else {
                val pageZoom = zoomOf(item)
                PhotoPage(item, sharedKey(item), cropOf(item)?.takeIf { !whole && !pageZoom.isZoomed }, pageZoom)
            }
        }

        AnimatedVisibility(
            // Always there over a cropped photo, not only with the bars (Nov 9, 2026: a toggle instead of holding).
            currentCrop != null && !showInfo && !playing,
            Modifier.align(Alignment.TopCenter).statusBarRoom().padding(top = 60.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            CropChip(showingWhole = whole, onToggle = { showWhole = !showWhole })
        }
        if (set != null) {
            AnimatedVisibility(
                chrome && !showInfo && !playing,
                Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                SetBar(
                    items, set, shownIndex, cropOf = { cropOf(it)?.takeIf { !whole } },
                    brief = { PhotoBrief(current, stories, Modifier.padding(start = 20.dp, end = 8.dp, bottom = 8.dp)) { showInfo = true } },
                    onIndex = { setIndex = it }, onPlay = { playing = true },
                )
            }
            AnimatedVisibility(playing, Modifier.align(Alignment.BottomCenter).navigationBarRoom(), enter = fadeIn(), exit = fadeOut()) {
                SlideshowBar(shownIndex, items.size, speed, { speed = it }, onStop = {
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
            Column(Modifier.fillMaxWidth().background(LibraryColors.PhotoFade).padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PhotoBrief(current, stories, Modifier.padding(start = 20.dp, end = 8.dp, bottom = 12.dp)) { showInfo = true }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ViewerAction(LibraryIcons.Edit, stringResource(R.string.viewer_write)) { editingCaption = true }
                    ShareButton(current, actions.share)
                }
            }
        }

        if (current.type != MediaType.VIDEO) {
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(LibraryColors.TopFade)
                    .statusBarRoom()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(LibraryIcons.Back, stringResource(R.string.library_back), tint = Color.White)
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
                    IconButton(onClick = { grid = true }) {
                        Icon(LibraryIcons.Grid, stringResource(R.string.set_all_photos), tint = Color.White)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(LibraryIcons.More, stringResource(R.string.set_more), tint = Color.White)
                        }
                        val context = LocalContext.current
                        val scope = rememberCoroutineScope()
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.viewer_write)) }, onClick = {
                                menu = false
                                editingCaption = true
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.library_viewer_details)) }, onClick = {
                                menu = false
                                showInfo = true
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.library_share)) }, onClick = {
                                menu = false
                                val item = current
                                scope.launch { if (!actions.share(item)) Toast.makeText(context, R.string.library_share_failed, Toast.LENGTH_LONG).show() }
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
private fun SetBar(items: List<MediaItem>, set: ViewerSet, index: Int, cropOf: (MediaItem) -> CropRect?, brief: @Composable () -> Unit, onIndex: (Int) -> Unit, onPlay: () -> Unit) {
    val item = items[index]
    val place = set.highlights.indexOf(item.id)
    Column(Modifier.fillMaxWidth().background(LibraryColors.PhotoFade).navigationBarRoom().padding(top = 24.dp, bottom = 10.dp)) {
        brief()
        if (items.size > 1) SetReel(items, set.highlights.toSet(), index, cropOf, onIndex)
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SetBarButton(
                if (place >= 0) "★" else "☆",
                stringResource(if (place >= 0) R.string.set_highlighted else R.string.set_highlight),
                Modifier.weight(1f),
            ) { set.setHighlight(item.id, place < 0) }
            Box(
                Modifier.size(60.dp).clip(CircleShape).background(Color.White).clickable(onClickLabel = stringResource(R.string.set_play), onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) { Icon(LibraryIcons.Play, stringResource(R.string.set_play), tint = Color.Black, modifier = Modifier.size(26.dp)) }
            SetBarButton("⌗", stringResource(R.string.set_recompose), Modifier.weight(1f), enabled = item.type != MediaType.VIDEO) { set.recompose(item.id) }
        }
    }
}

@Composable
private fun SetBarButton(glyph: String, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp).alpha(if (enabled) 1f else 0.4f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(glyph, color = Color.White, fontSize = 22.sp)
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** [zoom] is the viewer's for the photo shown, null for the pages beside it. */
@Composable
private fun PhotoPage(item: MediaItem, sharedKey: String, crop: CropRect?, zoom: PhotoZoom?) {
    if (!item.hasOriginalHere) return ServerPhotoPage(item, sharedKey, crop, zoom)
    val resolver = LocalContext.current.contentResolver
    val previews = LocalServerPreviews.current
    val preview = rememberThumbnail(item, THUMB_LARGE)
    // Already decoded when a slideshow made it ready: sharp from the first frame.
    val full by produceState(viewerImages.get(item.id), item.id) {
        if (value == null) value = viewerImage(resolver, previews, item)
    }
    val sharp = rememberZoomedOriginal(item, zoom) { loadImage(resolver, previews, item, zoomDecodeSide(item)) }
    val image = sharp ?: full?.asImageBitmap() ?: preview
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
private fun ServerPhotoPage(item: MediaItem, sharedKey: String, crop: CropRect?, zoom: PhotoZoom?) {
    val previews = LocalServerPreviews.current
    val resolver = LocalContext.current.contentResolver
    val cached by produceState<android.graphics.Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { cachedServerImage(previews, item) }
    }
    // The spinner is for a download only: an original already in the cache is just decoded, with nothing to wait for.
    var downloading by remember(item.id) { mutableStateOf(false) }
    // Already decoded when a slideshow made it ready: sharp from the first frame.
    val original by produceState(viewerImages.get(item.id), item.id) {
        if (value != null) return@produceState
        if (item.sha256?.let { previews?.cachedOriginal(it) } == null) downloading = true
        value = viewerImage(resolver, previews, item)
        downloading = false
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

/** The status bar's room, kept while the viewer hides it, so nothing moves when it shows for a moment. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Modifier.statusBarRoom() = windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)

/** The same for the gesture bar at the bottom. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Modifier.navigationBarRoom() = windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)

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
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { screen = it.toSize() },
        contentAlignment = Alignment.Center,
    ) {
        if (crop != null) {
            CropBox(crop, Modifier.aspectRatio(ratio * crop.w / crop.h).shared(sharedKey)) { Pictures(ContentScale.FillBounds) }
        } else {
            // Laid out at its zoomed size and place (not only drawn so), so closing flies to the tile from the zoomed
            // view rather than from the fitted photo.
            val placed = if (zoom != null) Modifier.zoomedLayout(zoom, ratio) else Modifier.aspectRatio(ratio)
            Box(placed.shared(sharedKey).detailOver(detail)) { Pictures(ContentScale.Crop) }
        }
    }
}

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

/**
 * Shows the poster frame until Play is tapped; stops when swiped away, and continues from there when played again. A
 * video only on the server streams from its original, and what was fetched stays cached ([ServerVideos]).
 */
@Composable
private fun VideoPage(item: MediaItem, sharedKey: String, isCurrent: Boolean) {
    var playing by rememberSaveable(item.id) { mutableStateOf(false) }
    var position by rememberSaveable(item.id) { mutableLongStateOf(0L) }
    LaunchedEffect(isCurrent) { if (!isCurrent) playing = false }

    if (playing) {
        VideoPlayer(item, startAt = position, onStopped = { position = it })
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // A video only on the server shows the poster already kept (the feed's or a grid's), asking for no new size.
            val poster = if (item.hasOriginalHere) rememberThumbnail(item, THUMB_LARGE) else rememberCachedServerImage(item)
            poster?.let { ViewerImage(it, sharedKey) }
            IconButton(
                onClick = { playing = true },
                colors = IconButtonDefaults.iconButtonColors(containerColor = LibraryColors.BadgeScrim),
                modifier = Modifier.size(72.dp),
            ) {
                Icon(LibraryIcons.Play, stringResource(R.string.library_viewer_play), tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}

/**
 * ExoPlayer with its controls, playing [item] from [startAt] (ms). Released when it leaves the screen, which stops a
 * stream from downloading further; [onStopped] gets where it was.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(item: MediaItem, startAt: Long, onStopped: (Long) -> Unit) {
    val context = LocalContext.current
    var player by remember(item.id) { mutableStateOf<ExoPlayer?>(null) }
    var failed by remember(item.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(item.id) {
        val playable = withContext(Dispatchers.IO) { runCatching { ServerVideos.playable(context, item) }.getOrNull() }
        if (playable == null) {
            failed = R.string.library_viewer_video_unavailable
            return@LaunchedEffect
        }
        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(playable.dataSource))
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        failed = R.string.library_viewer_video_failed
                    }
                })
                setMediaItem(playable.item, startAt)
                prepare()
                playWhenReady = true
            }
    }
    val stopped by rememberUpdatedState(onStopped)
    DisposableEffect(item.id) {
        onDispose {
            player?.let {
                stopped(it.currentPosition)
                it.release()
            }
            player = null
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { PlayerView(it).apply { setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
            update = { it.player = player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        failed?.let { Text(stringResource(it), color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(24.dp)) }
    }
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
            Text(formatDate(taken, "EEEdMMMyyyyjmm"), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1)
            if (tags.isNotEmpty()) {
                Text(tags.joinToString("  ") { "#$it" }, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onInfo) {
            Icon(LibraryIcons.Info, stringResource(R.string.library_viewer_details), tint = Color.White)
        }
    }
}
