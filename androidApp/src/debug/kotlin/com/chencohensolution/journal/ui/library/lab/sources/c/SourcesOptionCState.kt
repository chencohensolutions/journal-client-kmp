package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.library.MediaSource

/**
 * Show in the app as a table: a row per kind (Camera, WhatsApp, Screenshots, a folder, Other) and two columns, what
 * phones uploaded and what was imported, each cell shown or hidden on its own.
 */
internal data class SourcesOptionCState(val rows: List<Row>) {
    /** A kind; a column it has no files in is null. */
    data class Row(val source: MediaSource, val fromPhones: Cell?, val imported: Cell?)

    data class Cell(val count: Int, val shown: Boolean)

    /** The bar's "+N": every hidden cell. */
    val hiddenCount: Int get() = rows.sumOf { listOfNotNull(it.fromPhones, it.imported).count { cell -> !cell.shown } }
}

internal enum class SourcesOptionCColumn { FROM_PHONES, IMPORTED }

internal class SourcesOptionCActions(
    val toggle: (MediaSource, SourcesOptionCColumn) -> Unit = { _, _ -> },
    val back: () -> Unit = {},
)
