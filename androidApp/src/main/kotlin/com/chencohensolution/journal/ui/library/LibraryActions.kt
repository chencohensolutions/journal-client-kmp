package com.chencohensolution.journal.ui.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.chencohensolution.journal.R
import com.chencohensolution.journal.backup.BackupController
import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.server.ServerLibrary
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.storage.FreeUpController
import com.chencohensolution.journal.storage.FreeUpState
import com.chencohensolution.journal.work.FreeUpWorker
import com.chencohensolution.journal.work.currentWifiName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// What the library screens can do that needs Android permissions, system prompts or the server's files. Wired once
// here, so the screens only render state and call into it.

/** Read-only photo access. */
internal val PHOTO_PERMISSIONS = arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    // Read-only: without it MediaStore hides where photos were taken, which the Moments tab groups by.
    Manifest.permission.ACCESS_MEDIA_LOCATION,
)

/** The longer side of the frame kept for a video that leaves the phone. */
private const val VIDEO_POSTER_SIDE = 1920

internal class LibraryActions(
    /** Asks for photo access, or opens the app's settings page once Android stops asking. */
    val requestAccess: () -> Unit,
    /** With limited access: lets the user change which photos the app sees. */
    val changeSelection: () -> Unit,
    val backup: BackupActions,
    /** Downloads the original of a server photo into `files/originals`, reporting progress 0–1. */
    val downloadOriginal: suspend (MediaItem, (Float) -> Unit) -> Boolean,
    /** "Delete from this phone": only after the server confirms it has the file and a preview is kept. */
    val deleteFromPhone: suspend (MediaItem) -> RemovalOutcome,
    backgroundLocation: State<Boolean>,
    canTrashWithoutAsking: State<Boolean>,
    /** What previews, originals and videos take on this phone; the Settings screen counts it. */
    val previewBytes: MutableState<Long?>,
    storage: (openFreeUp: () -> Unit) -> StorageActions,
) {
    // Read through their State, not copied: a screen on the back stack keeps the LibraryActions it was first given.

    /** Whether "only on these Wi-Fi networks" can be checked while the app is in the background. */
    val backgroundLocation by backgroundLocation

    /** Whether Free up space can trash files without a system prompt ("Media management" access). */
    val canTrashWithoutAsking by canTrashWithoutAsking

    /** Opens the Free up space screen; set by the screens once navigation exists. */
    var openFreeUp: () -> Unit = {}

    val storage: StorageActions = storage { openFreeUp() }
}

/**
 * Builds the actions for this composition: the permission launchers and the state they change live here, so a
 * screen gets fresh lambdas on every recomposition but the launchers and the answers survive it.
 */
@Composable
internal fun libraryActions(
    library: LibraryController,
    libraryState: LibraryState,
    backup: BackupController,
    server: ServerFiles,
    freeUp: FreeUpController,
    freeUpState: FreeUpState,
    settings: SettingsController,
): LibraryActions {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // Photo access, and photo locations once access is given.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        library.onAccessRequested()
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { library.refresh() }
    var locationAsked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(libraryState.access) {
        val canRead = libraryState.access == PhotoAccess.FULL || libraryState.access == PhotoAccess.LIMITED
        if (canRead && !granted(Manifest.permission.ACCESS_MEDIA_LOCATION) && !locationAsked) {
            locationAsked = true
            locationLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }
    val requestAccess: () -> Unit = {
        // After two denials Android stops showing the prompt; only the app's settings page can grant access.
        val blocked = library.accessRequested &&
            libraryState.access == PhotoAccess.DENIED &&
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_IMAGES) == false
        if (blocked && activity != null) {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
            )
        } else {
            permissionLauncher.launch(PHOTO_PERMISSIONS)
        }
    }

    // Backup: notifications for its progress, and location for "only on these Wi-Fi networks".
    val backgroundLocationState = remember { mutableStateOf(granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }
    var backgroundLocation by backgroundLocationState
    LifecycleResumeEffect(Unit) {
        backgroundLocation = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        onPauseOrDispose {}
    }
    val addCurrentNetwork: () -> Unit = {
        scope.launch {
            val name = currentWifiName(context)
            if (name != null) backup.addWifiNetwork(name) else Toast.makeText(context, R.string.settings_backup_no_wifi, Toast.LENGTH_SHORT).show()
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val networkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) addCurrentNetwork()
    }
    val backgroundLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { backgroundLocation = it }
    val backupActions = BackupActions(
        setEnabled = { on ->
            if (on && !granted(Manifest.permission.POST_NOTIFICATIONS)) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            backup.setEnabled(on)
        },
        toggleSource = backup::toggleSource,
        setWifiOnly = backup::setWifiOnly,
        setChargingOnly = backup::setChargingOnly,
        setOrder = backup::setOrder,
        addCurrentNetwork = {
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                addCurrentNetwork()
            } else {
                networkLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        },
        removeNetwork = backup::removeWifiNetwork,
        allowBackgroundLocation = { backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
        backUpNow = backup::backUpNow,
    )

    // "Delete from this phone": Android's own prompt moves a library photo to the gallery's trash.
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { library.refresh() }
    val downloadOriginal: suspend (MediaItem, (Float) -> Unit) -> Boolean = { item, onProgress ->
        val sha = item.sha256
        sha != null && server.originals.download(sha, item.fileName ?: sha, onProgress)
    }
    val deleteFromPhone: suspend (MediaItem) -> RemovalOutcome = remove@{ item ->
        val sha = item.sha256
        if (sha == null || item.backup != BackupStatus.BackedUp) return@remove RemovalOutcome.NOT_ON_SERVER
        // Never without the server confirming it has these bytes, and a preview kept to show instead.
        when (server.library.confirms(sha)) {
            ServerLibrary.Confirmation.UNREACHABLE -> return@remove RemovalOutcome.UNREACHABLE
            ServerLibrary.Confirmation.MISSING -> {
                backup.uploadAgain(item)
                return@remove RemovalOutcome.NOT_ON_SERVER
            }
            ServerLibrary.Confirmation.HAS_IT -> Unit
        }
        val previewKept = if (item.type == MediaType.VIDEO) {
            // Keep a frame of it, made here from the video itself (the server makes posters too, once online).
            withContext(Dispatchers.IO) { videoPoster(context.contentResolver, item, VIDEO_POSTER_SIDE) }
                ?.let { (bytes, width, height) -> server.previews.keepPoster(sha, bytes, width, height); true } ?: false
        } else {
            server.previews.keepForViewing(sha)
        }
        if (!previewKept) return@remove RemovalOutcome.NO_PREVIEW
        if (!item.isOnDevice) {
            server.originals.delete(sha)
            RemovalOutcome.DELETED
        } else {
            trashLauncher.launch(IntentSenderRequest.Builder(moveToTrashRequest(context, item)).build())
            RemovalOutcome.ASKED
        }
    }

    // "Free up space": checked files that wait go to the trash with one system prompt, shown when a run has ended (the
    // notification at its end opens the app), or from Settings.
    val freeUpTrashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        freeUp.confirmationDone()
        FreeUpWorker.dismissConfirmation(context)
        library.refresh()
    }
    val moveWaitingToTrash: () -> Unit = {
        scope.launch {
            val items = freeUp.waitingItems()
            if (items.isEmpty()) {
                freeUp.confirmationDone()
            } else {
                freeUpTrashLauncher.launch(IntentSenderRequest.Builder(moveToTrashRequest(context, items)).build())
            }
        }
    }
    LifecycleResumeEffect(Unit) {
        freeUp.refreshPlan()
        onPauseOrDispose {}
    }
    LaunchedEffect(freeUpState.needsConfirmation) { if (freeUpState.needsConfirmation) moveWaitingToTrash() }
    val photoTrash = remember { AndroidPhotoTrash(context) }
    val canTrashWithoutAskingState = remember { mutableStateOf(photoTrash.canTrashWithoutAsking()) }
    var canTrashWithoutAsking by canTrashWithoutAskingState
    LifecycleResumeEffect(Unit) {
        canTrashWithoutAsking = photoTrash.canTrashWithoutAsking()
        onPauseOrDispose {}
    }
    val startFreeUp: () -> Unit = {
        if (!granted(Manifest.permission.POST_NOTIFICATIONS)) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        freeUp.start()
    }
    // Back from the system page for "Media management" access, given or not: the run starts either way.
    val manageMediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canTrashWithoutAsking = photoTrash.canTrashWithoutAsking()
        startFreeUp()
    }
    val previewBytes = remember { mutableStateOf<Long?>(null) }

    return LibraryActions(
        requestAccess = requestAccess,
        changeSelection = { permissionLauncher.launch(PHOTO_PERMISSIONS) },
        backup = backupActions,
        downloadOriginal = downloadOriginal,
        deleteFromPhone = deleteFromPhone,
        backgroundLocation = backgroundLocationState,
        canTrashWithoutAsking = canTrashWithoutAskingState,
        previewBytes = previewBytes,
        storage = { openFreeUp ->
            StorageActions(
                setPreviewsEnabled = settings::setPreviewsEnabled,
                setPreviewFormat = settings::setPreviewFormat,
                setPreviewQuality = settings::setPreviewQuality,
                setPreviewLimit = settings::setPreviewCacheGigabytes,
                clearPreviews = {
                    scope.launch {
                        server.storage.clear()
                        previewBytes.value = server.storage.usedBytes()
                    }
                },
                setRule = freeUp::setRule,
                freeUp = startFreeUp,
                allowTrashWithoutAsking = { manageMediaLauncher.launch(manageMediaSettingsIntent(context)) },
                moveWaitingToTrash = moveWaitingToTrash,
                toggleSource = freeUp::toggleSource,
                openFreeUp = openFreeUp,
            )
        },
    )
}
