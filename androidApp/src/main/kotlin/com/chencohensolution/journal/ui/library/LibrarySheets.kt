package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaItem

// The library's sheets (docs/screens/style.md): every sheet, confirmation and small form is a bottom sheet in the
// page's own colors (light on a light page, dark in the viewer), 16 dp top corners, the same drag handle. No
// AlertDialogs.

/** The grey bar at the top of every sheet. */
@Composable
private fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 36.dp, height = 4.dp).background(LibraryColors.Muted.copy(alpha = 0.45f), RoundedCornerShape(50)))
    }
}

/** A sheet over a page, in the page's colors; drawn in place in previews, where a sheet doesn't open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    if (LocalInspectionMode.current) {
        Box(Modifier.fillMaxSize().background(LibraryColors.BadgeScrim), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().background(LibraryColors.Background, LibraryShapes.Sheet)) {
                SheetHandle()
                content()
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = LibraryShapes.Sheet,
            containerColor = LibraryColors.Background,
            contentColor = LibraryColors.OnBackground,
            dragHandle = { SheetHandle() },
        ) { content() }
    }
}

/** A sheet's title. */
@Composable
internal fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = LibraryColors.OnBackground,
        style = LibraryType.PageTitle,
        modifier = modifier.padding(horizontal = 24.dp).semantics { heading() },
    )
}

/**
 * The buttons at the bottom of a sheet: [cancelLabel] (Cancel) and [confirmLabel], the confirm word in red when
 * [destructive], ink otherwise.
 */
@Composable
internal fun SheetButtons(
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    cancelLabel: String = stringResource(R.string.library_cancel),
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), horizontalArrangement = Arrangement.End) {
        TextAction(cancelLabel, onCancel)
        TextAction(confirmLabel, onConfirm, enabled = confirmEnabled, destructive = destructive)
    }
}

/** A question with Cancel and its button, inside a [PageSheet]; [photo] shown above it ("Not Mom" on a photo). */
@Composable
internal fun ConfirmContent(
    title: String,
    body: String?,
    confirmLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    photo: MediaItem? = null,
    cancelLabel: String = stringResource(R.string.library_cancel),
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().navigationBarRoom().padding(bottom = 16.dp)) {
        photo?.let {
            CroppedThumbnail(it, Modifier.padding(start = 24.dp, bottom = 14.dp).size(96.dp).clip(LibraryShapes.Tile))
        }
        SheetTitle(title)
        if (body != null) Text(body, color = LibraryColors.Muted, style = LibraryType.Body, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp))
        extra?.let { Column(Modifier.padding(start = 12.dp, end = 24.dp, top = 8.dp)) { it() } }
        SheetButtons(confirmLabel, onConfirm, onCancel, destructive, cancelLabel = cancelLabel)
    }
}

/**
 * A confirmation as a bottom sheet (replaces AlertDialog): [title], [body], Cancel and [confirmLabel]. [onDismiss] is
 * a swipe away or Cancel; [cancelLabel] / [onCancel] give Cancel another word and action ("Later").
 */
@Composable
internal fun ConfirmSheet(
    title: String,
    body: String?,
    confirmLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelLabel: String = stringResource(R.string.library_cancel),
    onCancel: () -> Unit = onDismiss,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) = PageSheet(onDismiss) {
    ConfirmContent(title, body, confirmLabel, destructive, onConfirm, onCancel, cancelLabel = cancelLabel, extra = extra)
}

/** A small form as a bottom sheet (name a moment, edit a call, add a folder): [title], [content], Cancel and [confirmLabel]. */
@Composable
internal fun FormSheet(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) = PageSheet(onDismiss) {
    Column(Modifier.fillMaxWidth().navigationBarRoom().imePadding().padding(bottom = 16.dp)) {
        SheetTitle(title)
        Column(
            Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp).verticalScroll(rememberScrollState(), enabled = !LocalInspectionMode.current),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
        SheetButtons(confirmLabel, onConfirm, onDismiss, destructive, confirmEnabled)
    }
}
