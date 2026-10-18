package com.chencohensolution.journal

import android.content.Context
import android.os.Build
import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.enrollment.AndroidDeviceKeyStore
import com.chencohensolution.journal.enrollment.EnrollmentController
import com.chencohensolution.journal.stories.StoryIds
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.FileStoryStore
import com.chencohensolution.journal.library.AndroidMediaLibrary
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.network.AndroidMtlsHttpClientFactory
import com.chencohensolution.journal.places.AndroidPlaceReader
import com.chencohensolution.journal.places.PhotoPlaces
import com.chencohensolution.journal.places.PlaceCache
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.settings.SharedPreferencesStore
import com.chencohensolution.journal.similarity.AndroidPhotoAnalyzer
import com.chencohensolution.journal.similarity.FingerprintCache
import com.chencohensolution.journal.similarity.PhotoSimilarity
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** Android entry point into the shared logic. */
fun createJournalController(context: Context): JournalController =
    JournalController(
        settings = JournalSettings(SharedPreferencesStore(context)),
        clientFactory = AndroidMtlsHttpClientFactory(context),
    )

/** The enrollment wizard; a successful enrollment switches [journal] to the new identity. */
fun createEnrollmentController(context: Context, journal: JournalController): EnrollmentController =
    EnrollmentController(
        settings = JournalSettings(SharedPreferencesStore(context)),
        clientFactory = AndroidMtlsHttpClientFactory(context),
        keyStore = AndroidDeviceKeyStore(),
        defaultDeviceName = Build.MODEL,
        onEnrolled = journal::useEnrolledIdentity,
    )

/** The device photo library (MediaStore). */
fun createLibraryController(context: Context): LibraryController =
    LibraryController(
        library = AndroidMediaLibrary(context),
        settings = JournalSettings(SharedPreferencesStore(context)),
    )

/** The user's choices on the Settings screen. */
fun createSettingsController(context: Context): SettingsController =
    SettingsController(
        JournalSettings(SharedPreferencesStore(context)),
        defaultSimilarityPercent = AndroidPhotoAnalyzer.DEFAULT_SAME_SCENE_PERCENT,
    )

/**
 * Stories kept on this device, in the app's private files folder (`files/journal/stories`). Photo fingerprints for
 * burst suggestions are cached in `cache/similarity`, where photos were taken and place names in `cache/places`.
 */
fun createStoriesController(context: Context, library: LibraryController, settings: SettingsController): StoriesController =
    StoriesController(
        store = FileStoryStore(FileSystem.SYSTEM, context.filesDir.toOkioPath() / "journal"),
        noteStore = FilePhotoNoteStore(FileSystem.SYSTEM, context.filesDir.toOkioPath() / "journal"),
        library = library,
        settings = settings,
        newId = StoryIds(JournalSettings(SharedPreferencesStore(context)), System::currentTimeMillis)::next,
        similarity = PhotoSimilarity(
            AndroidPhotoAnalyzer(context),
            FingerprintCache(FileSystem.SYSTEM, context.cacheDir.toOkioPath() / "similarity"),
        ),
        places = PhotoPlaces(AndroidPlaceReader(context), PlaceCache(FileSystem.SYSTEM, context.cacheDir.toOkioPath() / "places")),
    )
