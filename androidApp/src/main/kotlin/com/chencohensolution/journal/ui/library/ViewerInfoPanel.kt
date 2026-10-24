package com.chencohensolution.journal.ui.library

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.places.PlaceName
import java.util.Locale
import com.chencohensolution.journal.server.KeptHere
import com.chencohensolution.journal.server.KeptPreview
import com.chencohensolution.journal.server.PreviewFormat
import com.chencohensolution.journal.server.PreviewQuality
import com.chencohensolution.journal.storage.mediaKind
import com.chencohensolution.journal.storage.megapixels
import com.chencohensolution.journal.storage.percentOf
import kotlinx.coroutines.launch
import android.text.format.Formatter
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
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
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.stories.PhotoNote
import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

// The viewer's info panel: tags, the storyline, where it was taken, where the original is, what is kept on this phone,
// and the original's camera or video details.

/** Date, the photo's tags, whether it is in the storyline, where it was taken, backup status, file and camera details. */
@Composable
internal fun InfoPanel(
    item: MediaItem,
    note: PhotoNote?,
    onAddTag: () -> Unit,
    onRemoveTag: (String) -> Unit,
    /** "Remove" (false) or "Add back" (true) in the Storyline section. */
    setInStoryline: (Boolean) -> Unit,
    location: Pair<GeoPoint, PlaceName?>?,
    setPrivate: suspend (MediaItem, Boolean) -> Boolean,
    /** The map screen on where it was taken: a tap on the map. */
    openMap: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
    /** What the server found in it: people, text, similar photos. */
    findings: com.chencohensolution.journal.people.PhotoFindings = com.chencohensolution.journal.people.PhotoFindings.NONE,
    findingsActions: FindingsActions = FindingsActions(),
) {
    val taken = Instant.ofEpochMilli(item.takenAtMillis).atZone(ZoneId.systemDefault())
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 600.dp)
            .background(LibraryColors.Inverse, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .verticalScroll(rememberScrollState())
            .navigationBarRoom()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(formatDate(taken, "EEEEMMMMdyyyy"), color = LibraryColors.OnInverse, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(formatDate(taken, "jmm"), color = LibraryColors.OnInverseMuted, fontSize = 14.sp)
        }
        TagsRow(note?.tags.orEmpty(), onAddTag, onRemoveTag)
        if (note?.hasWriting == true) StorylineRow(note.inStoryline, setInStoryline)
        FindingsCards(findings, findingsActions)
        if (location != null) LocationCard(location, openMap)
        OriginalCard(item, setPrivate)
        DetailsCard(item)
    }
}

/** The panel's cards: a tint of its text color, so they read in light and dark. */
private val CardColor: Color @Composable get() = LibraryColors.OnInverse.copy(alpha = 0.06f)

/** Where it was taken: a map with a pin, the place and coordinates under it. A tap opens the map screen there. */
@Composable
private fun LocationCard(location: Pair<GeoPoint, PlaceName?>, openMap: (GeoPoint) -> Unit) {
    val (point, name) = location
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardColor)
            .clickable(onClickLabel = stringResource(R.string.library_info_open_map)) { openMap(point) }
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LocationMap(point, Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp)) {
            val place = listOfNotNull(name?.label, name?.city, name?.country).distinct().joinToString(", ")
            if (place.isNotEmpty()) Text(place, color = LibraryColors.OnInverse, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(String.format(Locale.ROOT, "%.5f, %.5f", point.latitude, point.longitude), color = LibraryColors.OnInverseMuted, fontSize = 13.sp)
        }
    }
}

/**
 * "Camera details" (a photo's EXIF) or "Video details" (codecs, rates, HDR), read from the original on this phone:
 * the usual facts, and a link that opens every tag. Until the viewer has downloaded the original, a line says so; the
 * details show when the download ends.
 */
@Composable
private fun DetailsCard(item: MediaItem) {
    val context = LocalContext.current
    val previews = LocalServerPreviews.current
    val progress by (previews?.originalProgress ?: remember { MutableStateFlow(emptyMap<String, Float>()) }).collectAsState()
    val downloading = item.sha256?.let { it in progress } == true
    // Unit while reading; null when the original isn't here.
    val details by produceState<Any?>(Unit, item.id, item.keptOriginal, downloading) {
        value = readOriginalDetails(context, previews, item)
    }
    val video = item.type == MediaType.VIDEO
    val shown = details as? OriginalDetails
    if (details == Unit || (shown != null && shown.count == 0)) return
    var open by remember(item.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(CardColor, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InfoHeading(stringResource(if (video) R.string.library_info_video_details else R.string.library_info_camera_details))
        if (shown == null) {
            Text(stringResource(R.string.library_info_details_waiting), color = LibraryColors.OnInverseMuted, fontSize = 14.sp)
            return@Column
        }
        if (shown.title != null || shown.subtitle != null) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                shown.title?.let { Text(it, color = LibraryColors.OnInverse, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                shown.subtitle?.let { Text(it, color = LibraryColors.OnInverseMuted, fontSize = 13.sp) }
            }
        }
        if (shown.chips.isNotEmpty()) DetailChips(shown.chips)
        Text(
            when {
                open -> stringResource(if (video) R.string.library_info_video_details_hide else R.string.library_info_exif_hide)
                else -> stringResource(if (video) R.string.library_info_video_details_all else R.string.library_info_exif_all, shown.count)
            },
            color = LibraryColors.Accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable { open = !open }.padding(vertical = 6.dp),
        )
        if (open) {
            shown.sections.forEach { section ->
                Text(section.name, color = LibraryColors.OnInverseMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                section.rows.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(label, color = LibraryColors.OnInverseMuted, fontSize = 13.sp, modifier = Modifier.width(130.dp))
                        Text(value, color = LibraryColors.OnInverse, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailChips(chips: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { chip ->
            Text(
                chip,
                color = LibraryColors.OnInverse,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(LibraryColors.Accent.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
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

/** With writing: "In the storyline" with Remove, or "Not in the storyline" with Add back. */
@Composable
private fun StorylineRow(inStoryline: Boolean, setInStoryline: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.storyline_title), color = LibraryColors.OnInverseMuted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (inStoryline) R.string.storyline_in else R.string.storyline_not_in),
                color = LibraryColors.OnInverse,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (inStoryline) {
                TextButton(onClick = { setInStoryline(false) }) {
                    Icon(LibraryIcons.Minus, null, tint = LibraryColors.Error, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.storyline_remove_short), color = LibraryColors.Error, modifier = Modifier.padding(start = 4.dp))
                }
            } else {
                TextButton(onClick = { setInStoryline(true) }) {
                    Icon(LibraryIcons.Plus, null, tint = LibraryColors.Accent, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.storyline_add_back), color = LibraryColors.Accent, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

/**
 * Where the original is (this phone, the server, or both; upload progress), its facts (file, size, dimensions, length,
 * type, source), the preview kept here for a photo only on the server, and "Mark private".
 */
@Composable
private fun OriginalCard(
    item: MediaItem,
    setPrivate: suspend (MediaItem, Boolean) -> Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = item.backup
    var confirmingPrivate by remember(item.id) { mutableStateOf(false) }
    var markingPrivate by remember(item.id) { mutableStateOf(false) }
    fun markPrivate(isPrivate: Boolean) {
        markingPrivate = true
        scope.launch {
            if (!setPrivate(item, isPrivate)) Toast.makeText(context, R.string.library_private_unreachable, Toast.LENGTH_LONG).show()
            markingPrivate = false
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(16.dp))
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

        InfoHeading(stringResource(R.string.library_info_original_title))
        val details = buildList {
            item.fileName?.let { add(stringResource(R.string.library_info_file) to it) }
            item.sizeBytes?.let { add(stringResource(R.string.library_info_size) to Formatter.formatShortFileSize(context, it)) }
            if (item.width > 0 && item.height > 0) add(stringResource(R.string.library_info_dimensions) to pixelsText(item.width, item.height))
            item.durationMillis?.let { add(stringResource(R.string.library_info_length) to formatDuration(it)) }
            item.mimeType?.let { add(stringResource(R.string.library_info_type) to typeName(it)) }
            add(stringResource(R.string.library_info_source) to sourceName(item.source))
        }
        FactGrid(details)
        if (!item.hasOriginalHere) ShownHereFacts(item)

        // Only a photo the server has can be marked: the mark is kept there, for every device.
        if (item.sha256 != null && status == BackupStatus.BackedUp && !markingPrivate) {
            if (item.isPrivate) {
                OriginalButton(LibraryIcons.Eye, stringResource(R.string.library_private_unmark)) { markPrivate(false) }
            } else {
                OriginalButton(LibraryIcons.Lock, stringResource(R.string.library_private_mark)) { confirmingPrivate = true }
            }
        }
    }
    if (confirmingPrivate) {
        AlertDialog(
            onDismissRequest = { confirmingPrivate = false },
            title = { Text(stringResource(R.string.library_private_mark_title)) },
            text = { Text(stringResource(R.string.library_private_mark_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingPrivate = false
                    markPrivate(true)
                }) { Text(stringResource(R.string.library_private_mark_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmingPrivate = false }) { Text(stringResource(R.string.library_cancel)) } },
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

/** A section title in the info panel ("Original", "Shown on this phone"). */
@Composable
private fun InfoHeading(text: String) {
    Text(text, color = LibraryColors.OnInverse, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
}

/** Facts two to a row. */
@Composable
private fun FactGrid(facts: List<Pair<String, String>>) {
    facts.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            pair.forEach { (label, value) -> InfoFact(label, value, Modifier.weight(1f)) }
            if (pair.size == 1) Box(Modifier.weight(1f))
        }
    }
}

/** "4000 × 3000 (12 MP)". */
private fun pixelsText(width: Int, height: Int): String =
    megapixels(width, height)?.let { "$width × $height ($it MP)" } ?: "$width × $height"

/** "HEIC", "JPEG", "DNG (RAW)", "MP4 video"; the MIME type itself for others. */
@Composable
private fun typeName(mimeType: String): String {
    val kind = mediaKind(mimeType) ?: return mimeType
    return when {
        kind.video -> stringResource(R.string.library_info_type_video, kind.name)
        kind.raw -> "${kind.name} (RAW)"
        else -> kind.name
    }
}

/**
 * A photo or video only on the server, as this phone has it ([PhoneStorage.keptHere]). "Shown on this phone": what the
 * viewer shows, the original when it was downloaded (it is used before any preview), else the largest preview, with its
 * size against the original's. "Kept on this phone": every size and format kept, the original, and how much of a video
 * is cached.
 */
@Composable
private fun ShownHereFacts(item: MediaItem) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val storage = LocalPhoneStorage.current
    val kept by produceState<KeptHere?>(null, item.sha256) {
        val sha = item.sha256 ?: return@produceState
        value = storage?.keptHere(sha, item.type == MediaType.VIDEO, item.width, item.height)
    }
    val here = kept ?: return
    val original = item.sizeBytes ?: 0
    fun sizeText(bytes: Long): String {
        val size = Formatter.formatShortFileSize(context, bytes)
        return percentOf(bytes, original)?.let { resources.getString(R.string.library_info_of_original, size, it) } ?: size
    }

    InfoHeading(stringResource(R.string.library_info_shown_title))
    val shown = here.previews.firstOrNull()
    FactGrid(
        buildList {
            when {
                item.type == MediaType.IMAGE && here.originalBytes != null ->
                    add(stringResource(R.string.library_info_format) to stringResource(R.string.library_info_original_title))
                shown == null -> add(stringResource(R.string.library_info_preview) to stringResource(R.string.library_info_preview_none))
                else -> {
                    add(stringResource(R.string.library_info_format) to keptFormat(shown))
                    add(stringResource(R.string.library_info_pixels) to pixelsText(shown.width, shown.height))
                    add(stringResource(R.string.library_info_size) to sizeText(shown.bytes))
                }
            }
        },
    )

    InfoHeading(stringResource(R.string.library_info_kept_title))
    if (here.isEmpty) {
        Text(stringResource(R.string.library_info_kept_nothing), color = LibraryColors.OnInverseMuted, fontSize = 14.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        here.previews.forEach { preview ->
            Text(
                "${preview.width} × ${preview.height} · ${keptFormat(preview)} · ${Formatter.formatShortFileSize(context, preview.bytes)}",
                color = LibraryColors.OnInverse,
                fontSize = 14.sp,
            )
        }
        here.originalBytes?.let { Text(stringResource(R.string.library_info_kept_original, sizeText(it)), color = LibraryColors.OnInverse, fontSize = 14.sp) }
        here.videoBytes?.let { bytes ->
            val cached = Formatter.formatShortFileSize(context, bytes)
            val text = if (original > 0) {
                stringResource(R.string.library_info_kept_video_of, cached, Formatter.formatShortFileSize(context, original))
            } else {
                stringResource(R.string.library_info_kept_video, cached)
            }
            Text(text, color = LibraryColors.OnInverse, fontSize = 14.sp)
        }
        Text(
            stringResource(R.string.library_info_kept_total, Formatter.formatShortFileSize(context, here.totalBytes)),
            color = LibraryColors.OnInverseMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** "JPEG XL · High", "WebP · made on this phone". */
@Composable
private fun keptFormat(preview: KeptPreview): String {
    val format = when (preview.format) {
        PreviewFormat.WEBP -> R.string.settings_storage_format_webp
        PreviewFormat.HEIF -> R.string.settings_storage_format_heif
        PreviewFormat.AVIF -> R.string.settings_storage_format_avif
        PreviewFormat.JPEG_XL -> R.string.settings_storage_format_jxl
        PreviewFormat.JPEG -> R.string.settings_storage_format_jpeg
        null -> null
    }
    val quality = when {
        preview.madeHere -> R.string.library_info_made_here
        else -> when (preview.quality) {
            PreviewQuality.LOW -> R.string.settings_storage_quality_low
            PreviewQuality.MEDIUM -> R.string.settings_storage_quality_medium
            PreviewQuality.HIGH -> R.string.settings_storage_quality_high
            PreviewQuality.MAX -> R.string.settings_storage_quality_max
            null -> null
        }
    }
    return listOfNotNull(format, quality).map { stringResource(it) }.joinToString(" · ")
}

@Composable
private fun OriginalButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(40.dp)) {
        Icon(icon, null, tint = LibraryColors.Accent, modifier = Modifier.size(18.dp))
        Text(label, color = LibraryColors.Accent, modifier = Modifier.padding(start = 6.dp))
    }
}
