package com.chencohensolution.journal.ui.library

/**
 * What option a's keep-on-phone part needs beside the backup settings: whether the app has "Media management" access,
 * which keeping JPEG XL needs (the app reads it from MediaStore.canManageMedia; BackupUi would carry it).
 */
internal data class BackupOptionAState(val canTrashWithoutAsking: Boolean)
