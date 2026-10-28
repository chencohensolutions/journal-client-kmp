package com.chencohensolution.journal.ui.library

/**
 * What option c's On this phone choice needs beside the backup settings: whether the app has "Media management" access,
 * which keeping JPEG XL needs (MediaStore.canManageMedia), and about how much space the smaller copies would give back on
 * this phone, null until known: the ledger's WAVs and JPEGs on the server times what FLAC and JPEG XL save (about half,
 * about a fifth), which nothing works out yet.
 */
internal data class BackupOptionCState(val canTrashWithoutAsking: Boolean, val savingsBytes: Long?)
