package com.chencohensolution.journal

import android.content.Context
import android.os.Build
import com.chencohensolution.journal.backup.AndroidMediaFiles
import com.chencohensolution.journal.backup.BackupController
import com.chencohensolution.journal.backup.BackupSettings
import com.chencohensolution.journal.backup.UploadLedger
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
import com.chencohensolution.journal.server.KeptOriginals
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.server.ServerLibrary
import com.chencohensolution.journal.server.ServerPreviews
import com.chencohensolution.journal.places.PhotoPlaces
import com.chencohensolution.journal.places.PlaceCache
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.settings.SharedPreferencesStore
import com.chencohensolution.journal.similarity.AndroidPhotoAnalyzer
import com.chencohensolution.journal.similarity.FingerprintCache
import com.chencohensolution.journal.similarity.PhotoSimilarity
import com.chencohensolution.journal.tls.SERVER_CA_PEM
import com.chencohensolution.journal.tls.decodePemCertificates
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

/**
 * The device photo library (MediaStore), with each photo's backup status from [backup], and the photos that are only on
 * the server from [server].
 */
fun createLibraryController(
    context: Context,
    backup: BackupController? = null,
    server: ServerLibrary? = null,
    originals: KeptOriginals? = null,
): LibraryController =
    LibraryController(
        library = AndroidMediaLibrary(context),
        settings = JournalSettings(SharedPreferencesStore(context)),
        backup = backup?.state,
        server = server,
        originals = originals,
    )

private var serverLibrary: ServerLibrary? = null
private var serverPreviews: ServerPreviews? = null
private var keptOriginals: KeptOriginals? = null
private var serverHttp: ServerHttp? = null

@Synchronized
private fun serverHttp(context: Context): ServerHttp =
    serverHttp ?: ServerHttp(JournalSettings(SharedPreferencesStore(context)), AndroidMtlsHttpClientFactory(context), decodePemCertificates(SERVER_CA_PEM))
        .also { serverHttp = it }

/** What the server holds, kept in `files/server/media.json`. One per process. */
@Synchronized
fun serverLibrary(context: Context): ServerLibrary =
    serverLibrary ?: ServerLibrary(
        settings = JournalSettings(SharedPreferencesStore(context)),
        clientFactory = AndroidMtlsHttpClientFactory(context),
        trustedCas = decodePemCertificates(SERVER_CA_PEM),
        fileSystem = FileSystem.SYSTEM,
        directory = context.filesDir.toOkioPath() / "server",
    ).also { serverLibrary = it }

/** Previews of server-only photos, cached in `cache/previews` (up to 10 GB). One per process. */
@Synchronized
fun serverPreviews(context: Context): ServerPreviews =
    serverPreviews ?: ServerPreviews(serverHttp(context), FileSystem.SYSTEM, context.cacheDir.toOkioPath() / "previews")
        .also { serverPreviews = it }

/** Originals downloaded from the server and kept in `files/originals`. One per process. */
@Synchronized
fun keptOriginals(context: Context): KeptOriginals =
    keptOriginals ?: KeptOriginals(serverHttp(context), FileSystem.SYSTEM, context.filesDir.toOkioPath() / "originals")
        .also { keptOriginals = it }

private var backupController: BackupController? = null

/**
 * Backup to the server. One per process, shared by the screens and the background worker, so both see the same
 * progress. Hashes and uploads are remembered in `files/backup/ledger.jsonl`. [scheduler] reschedules background runs
 * when the settings change and starts one for "Back up now"; it is set once, by the first caller.
 */
@Synchronized
fun backupController(context: Context, scheduler: BackupScheduler): BackupController =
    backupController ?: BackupController(
        settings = JournalSettings(SharedPreferencesStore(context)),
        ledger = UploadLedger(FileSystem.SYSTEM, context.filesDir.toOkioPath() / "backup"),
        library = AndroidMediaLibrary(context),
        files = AndroidMediaFiles(context),
        clientFactory = AndroidMtlsHttpClientFactory(context),
        trustedCas = decodePemCertificates(SERVER_CA_PEM),
        locate = AndroidPlaceReader(context).let { reader -> { item -> if (reader.canReadLocations) reader.location(item) else null } },
        onSettingsChanged = scheduler::settingsChanged,
        onBackUpNow = scheduler::backUpNow,
    ).also { backupController = it }

/** The app's background scheduling of backup runs (WorkManager). */
interface BackupScheduler {
    fun settingsChanged(settings: BackupSettings)
    fun backUpNow(settings: BackupSettings)
}

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
