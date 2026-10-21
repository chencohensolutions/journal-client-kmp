package com.chencohensolution.journal.ui.library

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.chencohensolution.journal.R
import com.chencohensolution.journal.stories.CropKind
import com.chencohensolution.journal.stories.CropRect
import com.chencohensolution.journal.stories.StoryTile
import com.chencohensolution.journal.stories.StoryView
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.stories.GroupView
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Longest side of a photo decoded for the viewer. */
private const val VIEWER_MAX_SIDE = 2560

internal class ViewerActions(
    val back: () -> Unit = {},
    val openGroup: (groupId: String) -> Unit = {},
    val setCover: (itemId: String) -> Unit = {},
    val removeFromGroup: (itemId: String) -> Unit = {},
    val toggleStory: (storyId: String, itemId: String, add: Boolean) -> Unit = { _, _, _ -> },
    val createStory: (title: String, itemId: String) -> Unit = { _, _ -> },
    val setCaption: (itemId: String, text: String) -> Unit = { _, _ -> },
    val toggleTag: (itemId: String, tag: String, add: Boolean) -> Unit = { _, _, _ -> },
    /** The photo now shown, on opening and on each swipe: it is the one that flies back to its tile on closing. */
    val showing: (itemId: String) -> Unit = {},
    /** Opens the crop screen for the photo, on the crop for how the phone is held. */
    val crop: (itemId: String, kind: CropKind) -> Unit = { _, _ -> },
    /** Downloads the original from the server and keeps it here; [onProgress] gets 0..1. False when it couldn't. */
    val downloadOriginal: suspend (item: MediaItem, onProgress: (Float) -> Unit) -> Boolean = { _, _ -> false },
    /** Removes the original from this phone, keeping it on the server and its preview here. */
    val deleteFromPhone: suspend (MediaItem) -> RemovalOutcome = { RemovalOutcome.UNREACHABLE },
    /** Marks the photo private on the server (true), or no longer private. False when the server couldn't be reached. */
    val setPrivate: suspend (MediaItem, Boolean) -> Boolean = { _, _ -> false },
)

/** What "Delete from this phone" did. */
internal enum class RemovalOutcome {
    /** Removed (an original the app had kept). */
    DELETED,

    /** Android's prompt to move it to the gallery's trash is showing. */
    ASKED,

    /** Nothing removed: the server can't be reached, or its preview couldn't be kept. */
    UNREACHABLE,

    /** Nothing removed: the server answered and doesn't have this photo. */
    NOT_ON_SERVER,

    /** Nothing removed: no preview could be kept to show it by (a video whose frame can't be read). */
    NO_PREVIEW,
}

/**
 * One photo or video at a time, swiping through [items]. [openedFrom] is the story the viewer was opened from, if
 * any, and [group] the group within it. See docs/screens/viewer.md.
 */
@Composable
internal fun ViewerScreen(
    items: List<MediaItem>,
    startId: String,
    stories: StoriesState,
    openedFrom: StoryView?,
    group: GroupView?,
    actions: ViewerActions,
) {
    val onBack = actions.back
    if (items.isEmpty()) {
        // The item was deleted from the device while open.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val pager = rememberPagerState(initialPage = items.indexOfFirst { it.id == startId }.coerceAtLeast(0)) { items.size }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var choosingStory by rememberSaveable { mutableStateOf(false) }
    var choosingTag by rememberSaveable { mutableStateOf(false) }
    var editingCaption by rememberSaveable { mutableStateOf(false) }
    // The whole photo instead of its crop, until turned back (kept while swiping).
    var showWhole by rememberSaveable { mutableStateOf(false) }
    // A photo's portrait crop shows with the phone upright, its landscape one sideways, fitted to this screen.
    val window = LocalWindowInfo.current.containerSize
    val screenRatio = if (window.width > 0 && window.height > 0) window.width.toFloat() / window.height else 9f / 19.5f
    val cropKind = if (screenRatio <= 1f) CropKind.PORTRAIT else CropKind.LANDSCAPE
    fun cropOf(item: MediaItem) =
        stories.note(item.id)?.crops?.get(cropKind)?.takeIf { item.type != MediaType.VIDEO }?.fittedTo(screenRatio, item.ratio)
    val current = items[pager.currentPage.coerceIn(items.indices)]
    val currentCrop = cropOf(current)
    LaunchedEffect(current.id) { actions.showing(current.id) }
    // A group is one stop here, its cover: that photo is shared with the group's tile, so closing on it lands there.
    val groupOfCover = remember(openedFrom, group) {
        (group?.tiles ?: openedFrom?.tiles).orEmpty().filterIsInstance<StoryTile.Group>().associate { it.group.cover.id to it.group.id }
    }
    fun sharedKey(item: MediaItem) = groupOfCover[item.id]?.let(SharedKey::group) ?: SharedKey.item(item.id)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, key = { items[it].id }, modifier = Modifier.fillMaxSize()) { page ->
            val item = items[page]
            if (item.type == MediaType.VIDEO) {
                VideoPage(item, sharedKey(item), isCurrent = page == pager.currentPage)
            } else {
                PhotoPage(item, sharedKey(item), cropOf(item)?.takeIf { !showWhole })
            }
        }

        if (currentCrop != null && !showInfo) {
            CropChip(
                showingWhole = showWhole,
                onToggle = { showWhole = !showWhole },
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 60.dp),
            )
        }
        if (!showInfo) {
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ViewerAction(LibraryIcons.Edit, stringResource(R.string.viewer_write)) { editingCaption = true }
                if (current.type != MediaType.VIDEO) {
                    ViewerAction(LibraryIcons.Crop, stringResource(R.string.viewer_crop)) { actions.crop(current.id, cropKind) }
                }
            }
        }

        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(LibraryColors.TopFade)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(LibraryIcons.Back, stringResource(R.string.library_back), tint = Color.White)
            }
            Text(
                stringResource(R.string.library_viewer_position, pager.currentPage + 1, items.size, dayLabel(current.day)),
                color = Color(0xFFB8C2BF),
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            // In a story or a group, a group inside it is one stop, shown as its cover; this opens the rest of it.
            val coverOf = if (group != null) group.coverStop(current.id) else openedFrom?.coverStop(current.id)
            if (coverOf != null) {
                TextButton(onClick = { actions.openGroup(coverOf.id) }) {
                    Icon(LibraryIcons.Stack, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.library_open_group, coverOf.itemCount),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            IconButton(
                onClick = { showInfo = !showInfo },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (showInfo) Color.White.copy(alpha = 0.16f) else Color.Transparent,
                ),
            ) {
                Icon(LibraryIcons.Info, stringResource(R.string.library_viewer_details), tint = Color.White)
            }
        }

        if (showInfo) {
            InfoPanel(
                current,
                note = stories.note(current.id),
                onEditCaption = { editingCaption = true },
                onAddTag = { choosingTag = true },
                onRemoveTag = { tag -> actions.toggleTag(current.id, tag, false) },
                memberOf = stories.storiesContaining(current.id),
                openedFrom = openedFrom,
                onAddToStory = { choosingStory = true },
                onRemove = { story -> actions.toggleStory(story.id, current.id, false) },
                group = group,
                onSetCover = { actions.setCover(current.id) },
                onRemoveFromGroup = { actions.removeFromGroup(current.id) },
                location = stories.places.location(current)?.let { point -> point to stories.places.name(point) },
                downloadOriginal = actions.downloadOriginal,
                deleteFromPhone = actions.deleteFromPhone,
                setPrivate = actions.setPrivate,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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
        WritingEditor(
            title = stringResource(R.string.library_note_title),
            initial = stories.note(current.id)?.caption.orEmpty(),
            onSave = {
                editingCaption = false
                actions.setCaption(current.id, it)
            },
            onDismiss = { editingCaption = false },
        )
    }
    if (choosingStory) {
        AddToStorySheet(
            itemIds = listOf(current.id),
            stories = stories,
            onToggle = { storyId, add -> actions.toggleStory(storyId, current.id, add) },
            onCreate = { title ->
                choosingStory = false
                actions.createStory(title, current.id)
            },
            onDismiss = { choosingStory = false },
        )
    }
}

@Composable
private fun PhotoPage(item: MediaItem, sharedKey: String, crop: CropRect?) {
    if (!item.hasOriginalHere) return ServerPhotoPage(item, sharedKey, crop)
    val resolver = LocalContext.current.contentResolver
    val previews = LocalServerPreviews.current
    val preview = rememberThumbnail(item, THUMB_LARGE)
    val full by produceState<android.graphics.Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { loadImage(resolver, previews, item, VIEWER_MAX_SIDE) }
    }
    val image = full?.asImageBitmap() ?: preview
    if (image != null) ViewerImage(image, sharedKey, crop)
}

/**
 * A photo only on the server: the best image of it already on this phone shows at once (asking the server for nothing),
 * while its original downloads into the cache (kept there); the original then fades in over it. A small spinner shows
 * while it downloads. When this phone can't decode the original (Samsung's DNG 1.7), a preview at screen size is asked
 * for instead; offline, the cached image stays.
 */
@Composable
private fun ServerPhotoPage(item: MediaItem, sharedKey: String, crop: CropRect?) {
    val previews = LocalServerPreviews.current
    val cached by produceState<android.graphics.Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { cachedServerImage(previews, item) }
    }
    var downloading by remember(item.id) { mutableStateOf(true) }
    val original by produceState<android.graphics.Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { serverOriginal(previews, item, VIEWER_MAX_SIDE) }
        downloading = false
    }
    val fade by animateFloatAsState(if (original != null) 1f else 0f, tween(ORIGINAL_FADE_MILLIS), label = "original")
    Box(Modifier.fillMaxSize()) {
        val base = cached ?: original
        if (base != null) {
            ViewerImage(base.asImageBitmap(), sharedKey, crop, over = original?.takeIf { cached != null }?.asImageBitmap(), overAlpha = fade)
        }
        if (downloading) {
            CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.8f),
                strokeWidth = 2.dp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 16.dp).size(18.dp),
            )
        }
    }
}

private const val ORIGINAL_FADE_MILLIS = 250

/**
 * The photo at its own shape (or its [crop]'s), centred: it looks fitted, and it is shared with the tile it was opened
 * from, so its crop opens up as the tile grows into it.
 */
@Composable
private fun ViewerImage(image: ImageBitmap, sharedKey: String, crop: CropRect? = null, over: ImageBitmap? = null, overAlpha: Float = 1f) {
    val ratio = image.width.toFloat() / image.height
    // [over] (the original, once downloaded) is drawn on top of [image] in the same place, fading in.
    @Composable
    fun Pictures(scale: ContentScale) {
        Image(image, contentDescription = null, contentScale = scale, modifier = Modifier.fillMaxSize())
        over?.let { Image(it, contentDescription = null, contentScale = scale, modifier = Modifier.fillMaxSize().alpha(overAlpha)) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (crop != null) {
            CropBox(crop, Modifier.aspectRatio(ratio * crop.w / crop.h).shared(sharedKey)) { Pictures(ContentScale.FillBounds) }
        } else {
            Box(Modifier.aspectRatio(ratio).shared(sharedKey)) { Pictures(ContentScale.Crop) }
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
