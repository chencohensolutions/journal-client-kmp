package com.chencohensolution.journal.ui.library

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.places.PlaceName
import java.util.Locale
import kotlinx.coroutines.launch
import android.text.format.Formatter
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import com.chencohensolution.journal.stories.PhotoNote
import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

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
 * The photo at its own shape (or its [crop]'s), centred: it looks fitted, and it is shared with the tile it was opened
 * from, so its crop opens up as the tile grows into it.
 */
@Composable
private fun ViewerImage(image: ImageBitmap, sharedKey: String, crop: CropRect? = null) {
    val ratio = image.width.toFloat() / image.height
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (crop != null) {
            CropBox(crop, Modifier.aspectRatio(ratio * crop.w / crop.h).shared(sharedKey)) {
                Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            }
        } else {
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.aspectRatio(ratio).shared(sharedKey),
            )
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

/** Shows the poster frame until Play is tapped; stops when swiped away. */
@Composable
private fun VideoPage(item: MediaItem, sharedKey: String, isCurrent: Boolean) {
    var playing by rememberSaveable(item.id) { mutableStateOf(false) }
    LaunchedEffect(isCurrent) { if (!isCurrent) playing = false }

    // A video only on the server isn't played yet (streaming it comes later); its poster shows.
    val uri = item.playableUri()
    if (playing && uri != null) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    setMediaController(MediaController(context).also { it.setAnchorView(this) })
                    setVideoURI(uri)
                    setOnPreparedListener { start() }
                }
            },
            onRelease = { it.stopPlayback() },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            rememberThumbnail(item, THUMB_LARGE)?.let { ViewerImage(it, sharedKey) }
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

/** Date, the photo's note and tags, its stories, backup status and file details. */
@Composable
private fun InfoPanel(
    item: MediaItem,
    note: PhotoNote?,
    onEditCaption: () -> Unit,
    onAddTag: () -> Unit,
    onRemoveTag: (String) -> Unit,
    memberOf: List<StoryView>,
    openedFrom: StoryView?,
    onAddToStory: () -> Unit,
    onRemove: (StoryView) -> Unit,
    group: GroupView?,
    onSetCover: () -> Unit,
    onRemoveFromGroup: () -> Unit,
    location: Pair<GeoPoint, PlaceName?>?,
    downloadOriginal: suspend (MediaItem, (Float) -> Unit) -> Boolean,
    deleteFromPhone: suspend (MediaItem) -> RemovalOutcome,
    modifier: Modifier = Modifier,
) {
    val taken = Instant.ofEpochMilli(item.takenAtMillis).atZone(ZoneId.systemDefault())
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 600.dp)
            .background(LibraryColors.Inverse, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(formatDate(taken, "EEEEMMMMdyyyy"), color = LibraryColors.OnInverse, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(formatDate(taken, "jmm"), color = LibraryColors.OnInverseMuted, fontSize = 14.sp)
        }
        CaptionBlock(note?.caption.orEmpty(), onEditCaption)
        TagsRow(note?.tags.orEmpty(), onAddTag, onRemoveTag)
        if (group != null) {
            GroupRow(group, isCover = group.cover.id == item.id, onSetCover, onRemoveFromGroup)
        } else {
            StoriesRow(memberOf, openedFrom?.takeIf { from -> memberOf.any { it.id == from.id } }, onAddToStory, onRemove)
        }
        OriginalCard(item, location, downloadOriginal, deleteFromPhone)
    }
}

/** The photo's own text, wherever it appears; tap to write or edit. */
@Composable
private fun CaptionBlock(caption: String, onEdit: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(14.dp))
            .clickable(onClickLabel = stringResource(R.string.library_writing_edit), onClick = onEdit)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.library_note_title), color = LibraryColors.OnInverseMuted, fontSize = 12.sp)
        Text(
            caption.ifBlank { stringResource(R.string.library_note_prompt) },
            color = if (caption.isBlank()) LibraryColors.OnInverseMuted else LibraryColors.OnInverse,
            fontFamily = FontFamily.Serif,
            fontSize = 16.sp,
            lineHeight = 23.sp,
        )
    }
}

/** Tag chips, each with ×, and "+ Tag". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsRow(tags: List<String>, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.library_info_tags), color = LibraryColors.OnInverseMuted, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.forEach { tag ->
                Row(
                    Modifier
                        .background(LibraryColors.Accent.copy(alpha = 0.12f), RoundedCornerShape(50))
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tag, color = LibraryColors.OnInverse, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    IconButton(onClick = { onRemove(tag) }, modifier = Modifier.size(36.dp)) {
                        Icon(LibraryIcons.Close, stringResource(R.string.library_remove_tag, tag), tint = LibraryColors.OnInverseMuted, modifier = Modifier.size(14.dp))
                    }
                }
            }
            TextButton(onClick = onAdd, modifier = Modifier.height(36.dp)) {
                Icon(LibraryIcons.Plus, null, tint = LibraryColors.Accent, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.library_tag), color = LibraryColors.Accent, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/** In a group: "Group: Fixing the car" with Set as cover and Remove from group. */
@Composable
private fun GroupRow(group: GroupView, isCover: Boolean, onSetCover: () -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.library_info_group), color = LibraryColors.OnInverseMuted, fontSize = 12.sp)
        Text(group.title ?: timeRange(group.items), color = LibraryColors.OnInverse, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (isCover) {
                Text(
                    stringResource(R.string.library_is_cover),
                    color = LibraryColors.OnInverseMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                TextButton(onClick = onSetCover, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(stringResource(R.string.library_set_cover), color = LibraryColors.Accent)
                }
            }
            TextButton(onClick = onRemove) {
                Icon(LibraryIcons.Minus, null, tint = LibraryColors.Error, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.library_remove_from_group), color = LibraryColors.Error, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/** "In: Japan trip, Dinner" with Add to story, and Remove when opened from one of them. */
@Composable
private fun StoriesRow(memberOf: List<StoryView>, removable: StoryView?, onAdd: () -> Unit, onRemove: (StoryView) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.library_info_stories), color = LibraryColors.OnInverseMuted, fontSize = 12.sp)
        Text(
            if (memberOf.isEmpty()) stringResource(R.string.library_info_no_stories) else memberOf.map { storyName(it) }.joinToString(),
            color = LibraryColors.OnInverse,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Icon(LibraryIcons.Plus, null, tint = LibraryColors.Accent, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.library_add_to_story), color = LibraryColors.Accent, modifier = Modifier.padding(start = 4.dp))
            }
            if (removable != null) {
                TextButton(onClick = { onRemove(removable) }) {
                    Icon(LibraryIcons.Minus, null, tint = LibraryColors.Error, modifier = Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.library_remove_from_story, storyName(removable)),
                        color = LibraryColors.Error,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Where the original is (this phone, the server, or both; upload progress), its facts (file, size, dimensions, length,
 * type, source, where it was taken), the preview kept here for a photo only on the server, and "Download original" or
 * "Delete from this phone".
 */
@Composable
private fun OriginalCard(
    item: MediaItem,
    location: Pair<GeoPoint, PlaceName?>?,
    downloadOriginal: suspend (MediaItem, (Float) -> Unit) -> Boolean,
    deleteFromPhone: suspend (MediaItem) -> RemovalOutcome,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = item.backup
    var downloading by remember(item.id) { mutableStateOf<Float?>(null) }
    var confirmingDelete by remember(item.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(LibraryColors.Accent.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                if (item.hasOriginalHere || status != BackupStatus.BackedUp) {
                    BackupBadge(status, 22.dp, originalHere = item.hasOriginalHere)
                } else {
                    Icon(LibraryIcons.CloudUp, null, tint = LibraryColors.Accent, modifier = Modifier.size(16.dp))
                }
            }
            Text(originalLabel(item), color = LibraryColors.OnInverse, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (status is BackupStatus.Uploading) {
                Text("${(status.progress * 100).roundToInt()}%", color = LibraryColors.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (status is BackupStatus.Uploading) {
            LinearProgressIndicator(
                progress = { status.progress },
                color = LibraryColors.Accent,
                trackColor = LibraryColors.Accent.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
        }

        val details = buildList {
            item.fileName?.let { add(stringResource(R.string.library_info_file) to it) }
            item.sizeBytes?.let { add(stringResource(R.string.library_info_size) to Formatter.formatShortFileSize(context, it)) }
            if (item.width > 0 && item.height > 0) add(stringResource(R.string.library_info_dimensions) to "${item.width} × ${item.height}")
            item.durationMillis?.let { add(stringResource(R.string.library_info_length) to formatDuration(it)) }
            item.mimeType?.let { add(stringResource(R.string.library_info_type) to it) }
            add(stringResource(R.string.library_info_source) to sourceName(item.source))
        }
        details.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pair.forEach { (label, value) -> InfoFact(label, value, Modifier.weight(1f)) }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
        if (location != null) {
            val (point, name) = location
            val coordinates = String.format(Locale.ROOT, "%.5f, %.5f", point.latitude, point.longitude)
            val place = listOfNotNull(name?.label, name?.city?.takeIf { it != name.label }).joinToString(", ")
            InfoFact(stringResource(R.string.library_info_location), if (place.isEmpty()) coordinates else "$place\n$coordinates")
        }
        if (!item.hasOriginalHere) KeptPreviewFact(item)

        downloading?.let { progress ->
            Text(stringResource(R.string.library_original_downloading, (progress * 100).roundToInt()), color = LibraryColors.OnInverseMuted, fontSize = 13.sp)
            LinearProgressIndicator(
                progress = { progress },
                color = LibraryColors.Accent,
                trackColor = LibraryColors.Accent.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
        }
        when {
            !item.hasOriginalHere && item.sha256 != null && downloading == null ->
                OriginalButton(LibraryIcons.Download, stringResource(R.string.library_original_download)) {
                    downloading = 0f
                    scope.launch {
                        val ok = downloadOriginal(item) { downloading = it }
                        downloading = null
                        if (!ok) Toast.makeText(context, R.string.library_original_download_failed, Toast.LENGTH_LONG).show()
                    }
                }
            item.hasOriginalHere && status == BackupStatus.BackedUp ->
                OriginalButton(LibraryIcons.Trash, stringResource(R.string.library_original_delete)) { confirmingDelete = true }
        }
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.library_original_delete_title)) },
            text = {
                val body = stringResource(if (item.isOnDevice) R.string.library_original_delete_body_gallery else R.string.library_original_delete_body)
                Text(if (item.type == MediaType.VIDEO) body + "\n\n" + stringResource(R.string.library_original_delete_video) else body)
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    scope.launch {
                        when (deleteFromPhone(item)) {
                            RemovalOutcome.UNREACHABLE -> Toast.makeText(context, R.string.library_original_unreachable, Toast.LENGTH_LONG).show()
                            RemovalOutcome.NOT_ON_SERVER -> Toast.makeText(context, R.string.library_original_not_on_server, Toast.LENGTH_LONG).show()
                            RemovalOutcome.NO_PREVIEW -> Toast.makeText(context, R.string.library_original_no_preview, Toast.LENGTH_LONG).show()
                            RemovalOutcome.DELETED, RemovalOutcome.ASKED -> Unit
                        }
                    }
                }) { Text(stringResource(R.string.library_original_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.library_cancel)) } },
        )
    }
}

@Composable
private fun InfoFact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = LibraryColors.OnInverseMuted, fontSize = 12.sp)
        Text(value, color = LibraryColors.OnInverse, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** The largest preview of a server-only photo kept on this phone: its pixels and size, or "None yet". */
@Composable
private fun KeptPreviewFact(item: MediaItem) {
    val context = LocalContext.current
    val previews = LocalServerPreviews.current
    val kept by produceState<Pair<String, Long>?>(null, item.sha256) {
        val sha = item.sha256 ?: return@produceState
        value = withContext(Dispatchers.IO) {
            previews?.kept(sha)?.firstOrNull()?.let { preview ->
                val size = if (preview.file.endsWith(".jxl")) {
                    runCatching { com.awxkee.jxlcoder.JxlCoder.getSize(java.io.File(preview.file).readBytes()) }.getOrNull()
                        ?.let { it.width to it.height }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(preview.file, bounds)
                    bounds.outWidth to bounds.outHeight
                }
                size?.let { (w, h) -> "$w × $h" to preview.bytes }
            }
        }
    }
    InfoFact(
        stringResource(R.string.library_info_preview),
        kept?.let { (pixels, bytes) -> stringResource(R.string.library_info_preview_value, pixels, Formatter.formatShortFileSize(context, bytes)) }
            ?: stringResource(R.string.library_info_preview_none),
    )
}

@Composable
private fun OriginalButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(40.dp)) {
        Icon(icon, null, tint = LibraryColors.Accent, modifier = Modifier.size(18.dp))
        Text(label, color = LibraryColors.Accent, modifier = Modifier.padding(start = 6.dp))
    }
}
