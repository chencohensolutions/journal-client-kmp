package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.journal.library.MediaSource

private val labState = SourcesOptionCState(
    listOf(
        SourcesOptionCState.Row(MediaSource.CAMERA, SourcesOptionCState.Cell(1240, true), SourcesOptionCState.Cell(38410, true)),
        SourcesOptionCState.Row(MediaSource.WHATSAPP, SourcesOptionCState.Cell(96, false), SourcesOptionCState.Cell(10282, false)),
        SourcesOptionCState.Row(MediaSource.SCREENSHOTS, SourcesOptionCState.Cell(128, false), SourcesOptionCState.Cell(2140, false)),
        SourcesOptionCState.Row(MediaSource.folder("Petcube"), SourcesOptionCState.Cell(5, true), null),
        SourcesOptionCState.Row(MediaSource.folder("Samsung Notes"), null, SourcesOptionCState.Cell(212, true)),
        SourcesOptionCState.Row(MediaSource.OTHER, SourcesOptionCState.Cell(31, true), SourcesOptionCState.Cell(3917, true)),
    ),
)

private fun SourcesOptionCState.Cell?.flip() = this?.copy(shown = !shown)

@Composable
private fun Live(content: @Composable (SourcesOptionCState, SourcesOptionCActions) -> Unit) {
    var state by remember { mutableStateOf(labState) }
    content(
        state,
        SourcesOptionCActions(
            toggle = { source, column ->
                state = state.copy(
                    rows = state.rows.map { row ->
                        when {
                            row.source != source -> row
                            column == SourcesOptionCColumn.FROM_PHONES -> row.copy(fromPhones = row.fromPhones.flip())
                            else -> row.copy(imported = row.imported.flip())
                        }
                    },
                )
            },
        ),
    )
}

// Fakes: the app doesn't know which files were imported (Media.uploadedBy "import" isn't kept in the library yet), so
// the counts are made up, as is an imported folder ("Samsung Notes": the import keeps only four kinds today); the
// switches don't change; toggles live only while the option is open.
internal val sourcesOptionC = LabOption("c", "Table", "A row per kind, a column for Phones and one for Imported") {
    Live { state, actions -> SourcesScreenC(state, actions) }
}

// Fakes: as option c; the "Showing" sheet is drawn open under the bar, not as a sheet.
internal val sourcesOptionCBar = LabOption("c-bar", "Table: filter bar", "Chips marked with a phone or a box") {
    Live { state, actions -> SourcesBarC(state, actions) }
}
