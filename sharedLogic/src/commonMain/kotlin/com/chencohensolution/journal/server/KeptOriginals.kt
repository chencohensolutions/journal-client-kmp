package com.chencohensolution.journal.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/**
 * Originals that earlier versions downloaded on request ("Download original", now gone: the viewer downloads originals
 * into the preview cache by itself), kept in the app's own storage (`files/originals/<sha256>.<ext>`), outside the
 * preview cache. They are still shown and played from here until "Delete from this phone" frees them. Never written to
 * the photo library.
 */
class KeptOriginals internal constructor(
    private val fileSystem: FileSystem,
    private val directory: Path,
) {
    private val _kept = MutableStateFlow<Map<String, Path>>(emptyMap())

    /** Kept originals by sha256. */
    val kept: StateFlow<Map<String, Path>> = _kept.asStateFlow()

    /** Reads what is kept; call once at start. */
    suspend fun load() = withContext(Dispatchers.IO) {
        if (!fileSystem.exists(directory)) return@withContext
        markNoMedia(fileSystem, directory)
        _kept.value = fileSystem.list(directory)
            .filter { !it.name.endsWith(".tmp") && !it.name.startsWith(".") }
            .associateBy { it.name.substringBefore('.') }
    }

    /** Removes the kept original of [sha256]; the server keeps its own. */
    suspend fun delete(sha256: String) = withContext(Dispatchers.IO) {
        _kept.value[sha256]?.let { fileSystem.delete(it, mustExist = false) }
        _kept.value = _kept.value - sha256
    }
}
