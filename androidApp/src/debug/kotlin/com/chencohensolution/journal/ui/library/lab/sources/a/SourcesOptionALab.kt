package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.journal.library.MediaSource

private val labState = SourcesOptionAState(
    fromPhones = listOf(
        SourcesOptionAState.Source(MediaSource.CAMERA, 1240, true),
        SourcesOptionAState.Source(MediaSource.WHATSAPP, 96, false),
        SourcesOptionAState.Source(MediaSource.SCREENSHOTS, 128, false),
        SourcesOptionAState.Source(MediaSource.folder("Petcube"), 5, true),
        SourcesOptionAState.Source(MediaSource.OTHER, 31, true),
    ),
    imported = listOf(
        SourcesOptionAState.Source(MediaSource.CAMERA, 38410, true),
        SourcesOptionAState.Source(MediaSource.WHATSAPP, 10282, true),
        SourcesOptionAState.Source(MediaSource.SCREENSHOTS, 2140, false),
        SourcesOptionAState.Source(MediaSource.OTHER, 3917, true),
    ),
)

private fun List<SourcesOptionAState.Source>.flip(source: MediaSource) = map { if (it.source == source) it.copy(shown = !it.shown) else it }

@Composable
private fun Live(content: @Composable (SourcesOptionAState, SourcesOptionAActions) -> Unit) {
    var state by remember { mutableStateOf(labState) }
    content(
        state,
        SourcesOptionAActions(
            toggleFromPhones = { state = state.copy(fromPhones = state.fromPhones.flip(it)) },
            toggleImported = { state = state.copy(imported = state.imported.flip(it)) },
        ),
    )
}

// Fakes: the app doesn't know which files were imported (Media.uploadedBy "import" isn't kept in the library yet), so
// both groups' counts are made up; the switches don't change; toggles live only while the option is open.
internal val sourcesOptionA = LabOption("a", "Two groups", "From your phones and Imported, each with its own pills") {
    Live { state, actions -> SourcesScreenA(state, actions) }
}

// Fakes: as option a; the "Showing" sheet is drawn open under the bar, not as a sheet.
internal val sourcesOptionABar = LabOption("a-bar", "Two groups: filter bar", "Imported chips after a divider, with a box icon") {
    Live { state, actions -> SourcesBarA(state, actions) }
}
