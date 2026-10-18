package com.chencohensolution.journal

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The app must never change or delete the user's photos: it only reads the photo library. This scans every source
 * file of both apps and the shared logic and fails if anything that can write to MediaStore or PhotoKit, or a
 * permission that allows it, appears. Adding such a thing on purpose means changing this test, deliberately.
 */
class PhotoSafetyTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private val forbidden = listOf(
        // Android: MediaStore and file writes outside the app's own storage.
        "createDeleteRequest", "createTrashRequest", "createWriteRequest", "createFavoriteRequest",
        "openOutputStream", "openFileDescriptor", "contentResolver.delete", "contentResolver.update",
        "contentResolver.insert", ".applyBatch(", "MediaStore.setRequireOriginal", "Environment.getExternalStorage",
        "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE", "MANAGE_MEDIA", "ACCESS_MEDIA_LOCATION",
        // iOS: PhotoKit changes, and the add-only permission.
        "performChanges", "PHAssetChangeRequest", "PHAssetCreationRequest", "PHAssetCollectionChangeRequest",
        "deleteAssets", "PHContentEditingOutput", "NSPhotoLibraryAddUsageDescription", "UIImageWriteToSavedPhotosAlbum",
    )

    /**
     * Read-only exceptions, each only in the files named (allowed by the owner on Oct 18, 2026, to group the Unsorted
     * tab by where photos were taken): MediaStore hides a photo's GPS unless the app holds ACCESS_MEDIA_LOCATION and
     * opens the original with setRequireOriginal, which returns a URI to read; neither can write.
     */
    private val readOnlyExceptions = mapOf(
        "MediaStore.setRequireOriginal" to setOf("AndroidPlaceReader.kt"),
        "ACCESS_MEDIA_LOCATION" to setOf("AndroidPlaceReader.kt", "AndroidManifest.xml", "LibraryApp.kt"),
    )

    @Test
    fun nothingCanModifyOrDeletePhotos() {
        val sources = listOf("sharedLogic/src", "androidApp/src", "iosApp/iosApp")
            .map { File(root, it) }
            .onEach { assertTrue(it.isDirectory, "missing $it") }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "swift", "xml", "plist") }.toList() }
            .filterNot { it.name == "PhotoSafetyTest.kt" }
        assertTrue(sources.size > 20, "found only ${sources.size} source files under $root")

        val hits = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                forbidden.firstOrNull { it in line && file.name !in readOnlyExceptions[it].orEmpty() }
                    ?.let { "${file.relativeTo(root)}:${index + 1}: uses $it" }
            }
        }
        if (hits.isNotEmpty()) fail("Code that could change or delete photos:\n" + hits.joinToString("\n"))
    }

    @Test
    fun theAndroidAppOnlyAsksToReadMedia() {
        val manifest = File(root, "androidApp/src/main/AndroidManifest.xml").readText()
        val permissions = Regex("""uses-permission android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        val allowed = setOf(
            "android.permission.INTERNET",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            // Read-only: un-hides GPS in what the app reads (see readOnlyExceptions).
            "android.permission.ACCESS_MEDIA_LOCATION",
            // Not about photos: Android 17 needs it to reach the journal server on the local network.
            "android.permission.ACCESS_LOCAL_NETWORK",
        )
        assertTrue(permissions.isNotEmpty() && allowed.containsAll(permissions), "unexpected permissions: ${permissions - allowed}")
    }
}
