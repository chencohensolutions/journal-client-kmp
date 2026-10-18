package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.stories.FocusPoint
import com.chencohensolution.journal.stories.PlacedTile
import com.chencohensolution.journal.stories.StoryTile
import com.chencohensolution.journal.stories.TileSize

/** "Arrange" with Reset and Done, over the story while its layout is being arranged. */
@Composable
internal fun ArrangeTopBar(onReset: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(LibraryColors.Raised)
            .statusBarsPadding()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.library_arrange_title),
            color = LibraryColors.OnBackground,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onReset) { Text(stringResource(R.string.library_arrange_reset), color = LibraryColors.OnBackground) }
        Button(
            onClick = onDone,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LibraryColors.Inverse, contentColor = LibraryColors.OnInverse),
        ) { Text(stringResource(R.string.library_arrange_done), fontWeight = FontWeight.Bold) }
    }
}

/**
 * The picked tile's size presets (Auto first), Earlier / Later among its day's tiles, and Focus; a hint until a
 * tile is picked.
 */
@Composable
internal fun ArrangePanel(
    tile: PlacedTile?,
    onSize: (TileSize?) -> Unit,
    onMove: (offset: Int) -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(LibraryColors.Raised)
            .navigationBarsPadding()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (tile == null) {
            Text(
                stringResource(R.string.library_arrange_hint),
                color = LibraryColors.Muted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
            )
            return@Column
        }
        val options = listOf<TileSize?>(null) + TileSize.entries
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(options, key = { it?.fileName ?: "auto" }) { size ->
                SizeOption(
                    size = size,
                    shown = size ?: tile.size,
                    selected = if (size == null) !tile.sized else tile.sized && tile.size == size,
                    onClick = { onSize(size) },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton(stringResource(R.string.library_arrange_earlier), Modifier.weight(1f)) { onMove(-1) }
            PanelButton(stringResource(R.string.library_arrange_later), Modifier.weight(1f)) { onMove(1) }
            PanelButton(stringResource(R.string.library_arrange_focus), Modifier.weight(1f), onFocus)
        }
    }
}

@Composable
private fun PanelButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .height(44.dp)
            .border(1.dp, LibraryColors.OnBackground.copy(alpha = 0.28f), RoundedCornerShape(12.dp)),
    ) { Text(label, color = LibraryColors.OnBackground, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

/** A size preset as its shape on a little 6-column grid, with its name; Auto shows the size it resolves to. */
@Composable
private fun SizeOption(size: TileSize?, shown: TileSize, selected: Boolean, onClick: () -> Unit) {
    val cell = 5.dp
    Column(
        Modifier
            .width(64.dp)
            .background(if (selected) LibraryColors.OnBackground.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(cell * 6, cell * 4), contentAlignment = Alignment.BottomStart) {
            Box(Modifier.fillMaxSize().border(1.dp, LibraryColors.Muted.copy(alpha = 0.5f), RoundedCornerShape(2.dp)))
            Box(
                Modifier
                    .size(cell * shown.columns, cell * minOf(shown.rows, 4))
                    .background(if (selected) LibraryColors.Warning else LibraryColors.OnBackground.copy(alpha = if (size == null) 0.35f else 0.7f), RoundedCornerShape(2.dp)),
            )
        }
        Text(
            stringResource(size.labelRes()),
            color = if (selected) LibraryColors.OnBackground else LibraryColors.Muted,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

private fun TileSize?.labelRes(): Int = when (this) {
    null -> R.string.library_size_auto
    TileSize.SMALL -> R.string.library_size_small
    TileSize.WIDE -> R.string.library_size_wide
    TileSize.TALL -> R.string.library_size_tall
    TileSize.HALF -> R.string.library_size_half
    TileSize.LARGE -> R.string.library_size_large
    TileSize.FULL -> R.string.library_size_full
}

/** The photo a tile shows: the photo itself, or a group's cover. */
private val PlacedTile.photo: MediaItem
    get() = when (val tile = tile) {
        is StoryTile.Media -> tile.item
        is StoryTile.Group -> tile.group.cover
    }

/**
 * The whole photo: tap the part that must stay in view when the tile crops it. Shows the tile as it will look
 * below the photo.
 */
@Composable
internal fun FocusEditor(tile: PlacedTile, onSave: (FocusPoint) -> Unit, onDismiss: () -> Unit) {
    var focus by remember(tile.tile.key) { mutableStateOf(tile.focus) }
    val thumbnail = rememberThumbnail(tile.photo, THUMB_LARGE)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = LibraryColors.Background) {
            Column(Modifier.systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel), color = LibraryColors.OnBackground) }
                    Text(
                        stringResource(R.string.library_arrange_focus),
                        color = LibraryColors.OnBackground,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onSave(focus) }) {
                        Text(stringResource(R.string.library_save), color = LibraryColors.Warning, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    stringResource(R.string.library_arrange_focus_hint),
                    color = LibraryColors.Muted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (thumbnail != null) {
                        Box(
                            Modifier
                                .aspectRatio(thumbnail.width.toFloat() / thumbnail.height)
                                // Pointer positions are measured from the left even in Hebrew, like the photo's own coordinates.
                                .pointerInput(Unit) {
                                    detectTapGestures { offset ->
                                        focus = FocusPoint.of(offset.x / size.width, offset.y / size.height)
                                    }
                                },
                        ) {
                            Image(thumbnail, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                            Canvas(Modifier.fillMaxSize()) {
                                val center = Offset(focus.x * size.width, focus.y * size.height)
                                drawCircle(Color.Black.copy(alpha = 0.4f), radius = 22.dp.toPx(), center = center, style = Stroke(5.dp.toPx()))
                                drawCircle(Color.White, radius = 22.dp.toPx(), center = center, style = Stroke(2.5.dp.toPx()))
                                drawCircle(Color.White, radius = 3.dp.toPx(), center = center)
                            }
                        }
                    }
                }
                if (thumbnail != null) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 20.dp)
                            .height(96.dp)
                            .aspectRatio(tile.size.columns.toFloat() / tile.size.rows)
                            .background(LibraryColors.Raised),
                    ) {
                        Image(thumbnail, null, contentScale = ContentScale.Crop, alignment = focus.alignment(), modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
