package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.places.PlaceName
import com.chencohensolution.journal.stories.PhotoNote

// Made-up data for the viewer's info panel in the UI lab. The lab has no originals, so its camera details show the
// line that waits for the original.

/** A located photo kept on this phone and on the server. */
internal val labInfoItem: MediaItem = PreviewData.tripPhoto.copy(
    fileName = "PXL_20261012_093512417.jpg",
    sizeBytes = 4_812_377,
    width = 4080,
    height = 3072,
    mimeType = "image/jpeg",
    sha256 = "9f2c41d0b7e5a3c6f18e2d4b7a90c3e5f61d28b4a7c9e0f3d5b6a1c8e2f4d7b9",
    backup = BackupStatus.BackedUp,
)

/** Its note: a title (not shown in the panel) and two tags. */
internal val labInfoNote: PhotoNote? = PreviewData.feedStories.note(labInfoItem.id)?.copy(tags = listOf("Haifa", "Family trip"))

internal val labInfoLocation: Pair<GeoPoint, PlaceName?> =
    GeoPoint(32.81431, 34.98712) to PlaceName(area = "Bahá'í Gardens", city = "Haifa", country = "Israel")

/** The info panel with what the server found: people, text and similar photos, above where it was taken. */
@Composable
internal fun LabFindingsPanel(modifier: Modifier) {
    InfoPanel(
        labInfoItem,
        note = labInfoNote,
        onAddTag = {},
        onRemoveTag = {},
        setInStoryline = {},
        location = labInfoLocation,
        setPrivate = { _, _ -> true },
        openMap = {},
        modifier = modifier,
        findings = PeopleLabData.findings,
    )
}

/** The panel as the viewer draws it: at the bottom, over the photo. */
@Composable
internal fun LabInfoBackdrop(panel: @Composable (Modifier) -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        panel(Modifier.align(Alignment.BottomCenter))
    }
}
