package com.chencohensolution.journal.stories

import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * Deletes the stories this device kept before the storyline replaced them (owner, Oct 21, 2026): `<root>/stories`,
 * and `<root>/events`, their older name. Photos and their notes (`<root>/media`) are not touched. Nothing happens
 * once they are gone.
 */
internal fun deleteOldStories(fileSystem: FileSystem, root: Path) {
    listOf(root / "stories", root / "events").forEach { dir ->
        try {
            if (fileSystem.exists(dir)) fileSystem.deleteRecursively(dir)
        } catch (e: IOException) {
            // Tried again on the next start.
        }
    }
}
