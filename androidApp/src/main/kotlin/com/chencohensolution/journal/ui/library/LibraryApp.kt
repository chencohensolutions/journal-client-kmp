package com.chencohensolution.journal.ui.library

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.chencohensolution.journal.backup.BackupActivity
import com.chencohensolution.journal.backup.BackupController
import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.local.LocalIndexes
import com.chencohensolution.journal.people.PageSort
import com.chencohensolution.journal.people.PeopleController
import com.chencohensolution.journal.stories.ExploreScope
import com.chencohensolution.journal.stories.FeedSection
import com.chencohensolution.journal.stories.bestFirst
import com.chencohensolution.journal.people.PhotoFindingsController
import com.chencohensolution.journal.createPersonController
import androidx.compose.ui.platform.LocalContext
import com.chencohensolution.journal.search.SearchController
import com.chencohensolution.journal.server.CamerasState
import com.chencohensolution.journal.server.SoundFoldersState
import com.chencohensolution.journal.server.KeptOriginals
import com.chencohensolution.journal.server.ServerIndexing
import com.chencohensolution.journal.server.ServerLibrary
import com.chencohensolution.journal.server.ServerPreviews
import com.chencohensolution.journal.settings.KindsStatus
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.stories.CropKind
import com.chencohensolution.journal.stories.PhotoCrops
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.storage.FreeUpController
import com.chencohensolution.journal.storage.PhoneStorage

/**
 * The server's copies: its list of photos, their previews kept here, originals downloaded and kept, and what is kept
 * on this phone in all (previews, originals and videos under one limit).
 */
class ServerFiles(val library: ServerLibrary, val previews: ServerPreviews, val originals: KeptOriginals, val storage: PhoneStorage)

/** Everything after enrollment: photo access, then the journal and its screens. */
@Composable
fun LibraryApp(
    journal: JournalController,
    library: LibraryController,
    stories: StoriesController,
    settings: SettingsController,
    backup: BackupController,
    server: ServerFiles,
    freeUp: FreeUpController,
    search: SearchController,
    people: PeopleController,
    findings: PhotoFindingsController,
    localIndexes: LocalIndexes,
    serverIndexing: ServerIndexing,
    onRegisterAgain: () -> Unit,
) {
    // Sound recordings play while the app is in front: leaving it pauses them (no background playing).
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val audioPlayer = remember { AudioPlayer(context.applicationContext, scope) }
    DisposableEffect(audioPlayer) { onDispose { audioPlayer.stop() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { audioPlayer.pause() }
    CompositionLocalProvider(
        LocalServerPreviews provides server.previews,
        LocalPhoneStorage provides server.storage,
        LocalServerLibrary provides server.library,
        LocalAudioPlayer provides audioPlayer,
        LocalExploreReads provides remember(stories) { DatabaseExploreReads(stories) },
    ) {
        LibraryScreens(journal, library, stories, settings, backup, server, freeUp, search, people, findings, localIndexes, serverIndexing, onRegisterAgain)
    }
}

/** The routing table: each [Route] on the back stack rendered as its screen, with the actions wired to the controllers. */
@Composable
private fun LibraryScreens(
    journal: JournalController,
    library: LibraryController,
    stories: StoriesController,
    settings: SettingsController,
    backup: BackupController,
    server: ServerFiles,
    freeUp: FreeUpController,
    search: SearchController,
    people: PeopleController,
    findings: PhotoFindingsController,
    localIndexes: LocalIndexes,
    serverIndexing: ServerIndexing,
    onRegisterAgain: () -> Unit,
) {
    val phoneSearchState by localIndexes.state.collectAsStateWithLifecycle()
    val indexingState by serverIndexing.state.collectAsStateWithLifecycle()
    val journalState by journal.state.collectAsStateWithLifecycle()
    val features by server.library.features.collectAsStateWithLifecycle()
    val peopleState by people.state.collectAsStateWithLifecycle()
    val findingsState by findings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The photos pages opened the viewer on, for it to swipe through.
    val pageLists = remember { PageLists() }
    val searchState by search.state.collectAsStateWithLifecycle()
    val freeUpState by freeUp.state.collectAsStateWithLifecycle()
    val backupState by backup.state.collectAsStateWithLifecycle()
    val serverUsage by server.library.usage.collectAsStateWithLifecycle()
    val syncProgress by server.library.syncProgress.collectAsStateWithLifecycle()
    val hiddenEverywhere by server.library.hiddenEverywhere.collectAsStateWithLifecycle()
    // Your cameras' last list, for Settings' row: asked when Settings opens, and kept up by the page while it is open.
    var camerasState by remember { mutableStateOf<CamerasState>(CamerasState.Loading) }
    // Sound folders' last list, for its row, the same way.
    var soundFoldersState by remember { mutableStateOf<SoundFoldersState>(SoundFoldersState.Loading) }
    val backupRunning = (backupState.activity as? BackupActivity.Running)?.progress
    val settingsState by settings.state.collectAsStateWithLifecycle()
    val libraryState by library.state.collectAsStateWithLifecycle()
    val storiesState by stories.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current

    LaunchedEffect(Unit) { journal.connect() }
    // The search page offers people's faces once the server says it has face search.
    LaunchedEffect(features?.faces) { if (features?.faces == true) people.load() }
    // Picks up photos taken and permission changes made while the app was in the background.
    LifecycleResumeEffect(Unit) {
        library.refresh()
        // The indexes kept on this phone catch up once a day.
        localIndexes.updateIfDue()
        onPauseOrDispose {}
    }
    val actions = libraryActions(library, libraryState, backup, server, freeUp, freeUpState, settings)

    if (libraryState.access == PhotoAccess.NOT_DETERMINED) {
        PhotoAccessScreen(onAllow = actions.requestAccess, onNotNow = library::skipAccess)
        return
    }

    var stack by rememberSaveable(stateSaver = BackStackSaver) { mutableStateOf(listOf<Route>(Route.Journal)) }
    // The photo the viewer shows, for the screen under it: that screen scrolls its tile into view to land on.
    var landing by remember { mutableStateOf<Landing?>(null) }
    // "Show in Moments" from the viewer: back to the journal, on its Moments tab at that photo.
    var showInMoments by remember { mutableStateOf<ShowInMoments?>(null) }
    val push: (Route) -> Unit = {
        landing = null
        stack = stack + it
    }
    val pop: () -> Unit = { stack = stack.dropLast(1) }
    actions.openFreeUp = { push(Route.FreeUp) }
    // A face's sheet, in a photo's details and after Edit faces: corrections go through the people controller.
    val faceActions = FaceActions(
        confirm = people::confirm,
        notPerson = { face, id -> people.notPerson(face, id) },
        move = people::moveFace,
        name = people::nameFace,
        openPerson = { push(Route.Person(it)) },
    )
    // A photo of a town's or year's page: its row of the page (by date), or, Best first, the page's photos in that order.
    // The page's photos are kept for the viewer (it reads them again from the database only when they aren't).
    val openInScope: (ExploreScope, FeedSection?, MediaItem, List<MediaItem>, PageSort) -> Unit = { scope, section, item, photos, sort ->
        if (sort == PageSort.BEST || section == null) {
            val key = "best:" + scope.hashCode()
            pageLists[key] = bestFirst(photos)
            push(Route.Viewer(null, item.id, list = key))
        } else {
            pageLists[scopeListKey(scope, section.key)] = section.items
            push(Route.Viewer(section.key, item.id, scope = scope))
        }
    }
    val exploreReads = LocalExploreReads.current
    val coroutines = rememberCoroutineScope()
    var previewBytes by actions.previewBytes
    // A function, not a value: each screen on the back stack keeps the content it was first given, so it has to read
    // the state when it draws.
    fun storageUi() = StorageUi(previewBytes, settingsState.previewCacheGigabytes, freeUpState, actions.canTrashWithoutAsking, settingsState.previewOptions)

    // The library follows the system theme; the viewer is always dark, like a photo app. Each screen gets its own
    // theme so the viewer turns dark with its transition, not the screen it opens from.
    val systemDark = isSystemInDarkTheme()
    SystemBars(activity as? ComponentActivity, stack.last().let { it is Route.Viewer || it is Route.Crop || it is Route.Audio } || systemDark)
    // Each entry keeps its saveable state (scroll positions among it) while it is under the top of the stack.
    SharedTransitionLayout {
        CompositionLocalProvider(
            LocalSharedTransitionScope provides this,
            LocalShowOnPhoneBadge provides settingsState.showOnPhoneBadge,
            // Show in the app's pills in the "Showing" sheets of Moments and Explore.
            LocalShownSources provides ShownSourcesChoice(
                storiesState.shownSources,
                remember(settings) { ShownSourcesActions(settings::toggleFeedSource, settings::toggleImported, { push(Route.Hidden) }) },
                hiddenEverywhere,
            ),
        ) {
            NavDisplay(
                backStack = stack.mapIndexed(::StackEntry),
                onBack = pop,
                sharedTransitionScope = this,
                transitionSpec = { sharedAxis(forward = true) },
                popTransitionSpec = { sharedAxis(forward = false) },
                predictivePopTransitionSpec = { sharedAxis(forward = false) },
                entryProvider = { entry ->
                    val opensFromTile = entry.route is Route.Viewer
                    NavEntry(entry, contentKey = entry.contentKey, metadata = if (opensFromTile) FadeAroundSharedElements else emptyMap()) {
                        val route = entry.route
                        val landingHere = landing?.takeIf { it.depth == entry.depth }?.itemId
                        LibraryTheme(dark = route is Route.Viewer || route is Route.Crop || route is Route.Audio || systemDark) {
                            when (route) {
                                Route.Journal -> JournalHomeScreen(
                                    // Moments first and opened on start (asked Oct 24, 2026); previews keep the storyline.
                                    initialTab = HomeTab.MOMENTS,
                                    loadAhead = settingsState.momentsPaging.loadAhead,
                                    library = libraryState,
                                    stories = storiesState,
                                    health = journalState.health,
                                    actions = HomeActions(
                                        openItem = { section, item -> push(Route.Viewer(section.key, item.id)) },
                                        openStoryline = { push(Route.Viewer(null, it.id, storyline = true)) },
                                        openTags = { push(Route.Tags) },
                                        toggleSource = settings::toggleFeedSource,
                                        openSettings = { push(Route.Settings) },
                                        allowAccess = actions.requestAccess,
                                        changeSelection = actions.changeSelection,
                                        registerAgain = onRegisterAgain,
                                        setWriting = stories::setWriting,
                                        restoreWriting = stories::restoreWriting,
                                        crop = { push(Route.Crop(it, CropKind.MOMENTS)) },
                                        merge = stories::mergeInFeed,
                                        takeOutOfSet = stories::takeOutOfSet,
                                        turnSet = stories::turnSet,
                                        setInStoryline = stories::setInStoryline,
                                        toggleTag = { itemId, tag, add ->
                                            if (add) stories.addTag(listOf(itemId), tag) else stories.removeTag(listOf(itemId), tag)
                                        },
                                        explore = ExploreActions(
                                            openPlace = { push(Route.Place(it)) },
                                            openCountry = { push(Route.Country(it)) },
                                            openYear = { year, month -> push(Route.Year(year, month)) },
                                            openMap = { push(Route.Map()) },
                                            toggleSource = settings::toggleFeedSource,
                                            openPerson = { push(Route.Person(it)) },
                                            loadPeople = people::load,
                                            reloadPeople = people::reload,
                                        ),
                                        showOnRow = stories::showOnRow,
                                        loadMoreMoments = stories::loadMoreMoments,
                                        loadMomentsTo = stories::loadMomentsTo,
                                        loadMomentsToItem = stories::loadMomentsToItem,
                                        hideOnRow = stories::hideOnRow,
                                        alwaysShow = { section, source ->
                                            settings.alwaysShow(section.days, source)
                                            stories.hideOnRow(section.key, source)
                                        },
                                        openRevealed = { section, source, item ->
                                            push(Route.Viewer(section.key, item.id, revealed = source))
                                        },
                                        openRecording = { push(Route.Audio(it)) },
                                        search = SearchActions(
                                            search = search::search,
                                            clear = search::clear,
                                            chooseYear = search::chooseYear,
                                            chooseTown = search::chooseTown,
                                            loadMore = search::loadMore,
                                            openItem = { push(Route.Viewer(null, it.id, search = true)) },
                                            prepare = search::prepare,
                                            addPerson = search::addPerson,
                                            removePerson = search::removePerson,
                                            markPrivate = { items -> actions.markPrivate(items).also { if (it) search.madePrivate(items) } },
                                            showHiddenSources = search::showHiddenSources,
                                        ),
                                    ),
                                    landing = landingHere,
                                    showInMoments = showInMoments,
                                    backupRunning = backupRunning,
                                    search = searchState,
                                    people = peopleState.takeIf { features?.faces == true },
                                )
                                is Route.Country -> CountryScreen(
                                    country = route.country,
                                    library = libraryState,
                                    stories = storiesState,
                                    onOpenPlace = { push(Route.Place(it)) },
                                    onBack = pop,
                                )
                                is Route.Place -> {
                                    var year by rememberSaveable { mutableStateOf<Int?>(null) }
                                    var sort by rememberSaveable { mutableStateOf(PageSort.NEWEST) }
                                    PlaceScreen(
                                        town = route.town,
                                        library = libraryState,
                                        stories = storiesState,
                                        year = year,
                                        onYear = { year = it },
                                        onOpenItem = { scope, section, item, photos -> openInScope(scope, section, item, photos, sort) },
                                        onBack = pop,
                                        landing = landingHere,
                                        onToggleSource = settings::toggleFeedSource,
                                        sort = sort,
                                        onSort = if (features?.aesthetic == true) { chosen: PageSort -> sort = chosen } else null,
                                    )
                                }
                                is Route.Year -> {
                                    var month by rememberSaveable { mutableStateOf(route.month) }
                                    var sort by rememberSaveable { mutableStateOf(PageSort.NEWEST) }
                                    YearScreen(
                                        year = route.year,
                                        library = libraryState,
                                        stories = storiesState,
                                        month = month,
                                        onMonth = { month = it },
                                        onOpenItem = { scope, section, item, photos -> openInScope(scope, section, item, photos, sort) },
                                        onBack = pop,
                                        landing = landingHere,
                                        onToggleSource = settings::toggleFeedSource,
                                        sort = sort,
                                        onSort = if (features?.aesthetic == true) { chosen: PageSort -> sort = chosen } else null,
                                    )
                                }
                                is Route.Map -> MapScreen(
                                    library = libraryState,
                                    stories = storiesState,
                                    at = route.at,
                                    actions = MapActions(
                                        back = pop,
                                        toggleSource = settings::toggleFeedSource,
                                        // The photos in view, read from the database (step 6c) and kept for the viewer.
                                        openPhotos = { scope, itemId ->
                                            coroutines.launch {
                                                val photos = scopeViewerItems(exploreReads, libraryState, storiesState, scope, null)
                                                val first = itemId ?: photos.firstOrNull()?.id ?: return@launch
                                                pageLists[scopeListKey(scope, null)] = photos
                                                push(Route.Viewer(null, first, scope = scope))
                                            }
                                        },
                                    ),
                                )
                                Route.Settings, is Route.SettingsPageRoute -> {
                                    // Search's row says how the server's reading of this user's photos is going.
                                    LaunchedEffect(features?.indexing) { if (features?.indexing == true) serverIndexing.refresh() }
                                    // Your cameras' row says how many cameras there are and how many aren't the user's.
                                    LaunchedEffect(features?.cameras) {
                                        if (features?.cameras == true) server.library.cameras()?.let { camerasState = CamerasState.Ready(it) }
                                    }
                                    // Sound folders' row says how many folders there are and how many are merged.
                                    LaunchedEffect(features?.soundFolders) {
                                        if (features?.soundFolders == true) server.library.soundFolders()?.let { soundFoldersState = SoundFoldersState.Ready(it) }
                                    }
                                    // Asked on open, and again when a backup run ends, so it follows the uploads.
                                    LaunchedEffect(backupState.activity is BackupActivity.Running) {
                                        if (backupState.activity !is BackupActivity.Running) server.library.refreshUsage()
                                    }
                                    // Counted on open, and again (after trimming) when the limit changes.
                                    LaunchedEffect(settingsState.previewCacheGigabytes) {
                                        server.storage.trimToLimit()
                                        previewBytes = server.storage.usedBytes()
                                    }
                                    SettingsScreen(
                                        settings = settingsState,
                                        page = (route as? Route.SettingsPageRoute)?.page ?: SettingsPage.MAIN,
                                        kinds = KindsStatus.of(
                                            libraryState.sourceTotals(),
                                            libraryState.sounds,
                                            backupState.soundsNotBackedUp(),
                                            hiddenEverywhere,
                                        ),
                                        actions = SettingsActions(
                                            setSayWhatsHidden = settings::setSayWhatsHidden,
                                            setShowFilterBar = settings::setShowFilterBar,
                                            setGroupingMode = settings::setGroupingMode,
                                            setGapHours = settings::setGapHours,
                                            setSimilarityPercent = settings::setSimilarityPercent,
                                            resetSimilarity = settings::resetSimilarity,
                                            openHidden = { push(Route.Hidden) },
                                            openCameras = { push(Route.Cameras) },
                                            openSoundFolders = { push(Route.SoundFolders) },
                                            setShowOnPhoneBadge = settings::setShowOnPhoneBadge,
                                            setShareJpegXlAsJpeg = settings::setShareJpegXlAsJpeg,
                                            openSearch = { push(Route.SearchSettings) },
                                            openPage = { push(Route.SettingsPageRoute(it)) },
                                            openBackup = { push(Route.BackupSettings) },
                                            setMomentsRowsPerPage = settings::setMomentsRowsPerPage,
                                            setMomentsLoadAhead = settings::setMomentsLoadAhead,
                                            resetMomentsPaging = settings::resetMomentsPaging,
                                            back = pop,
                                        ),
                                        phoneSearch = phoneSearchState.takeIf { features?.localIndexes == true },
                                        indexing = indexingState.takeIf { features?.indexing == true && it.loaded },
                                        backup = BackupUi(backupState, libraryState.notBackedUpCount, actions.backgroundLocation, serverUsage, storiesState.feed.counts),
                                        backupActions = actions.backup,
                                        storage = storageUi(),
                                        storageActions = actions.storage,
                                        syncProgress = syncProgress,
                                        cameras = camerasState.takeIf { features?.cameras == true },
                                        soundFolders = soundFoldersState.takeIf { features?.soundFolders == true },
                                    )
                                }
                                Route.BackupSettings -> {
                                    LaunchedEffect(backupState.activity is BackupActivity.Running) {
                                        if (backupState.activity !is BackupActivity.Running) server.library.refreshUsage()
                                    }
                                    BackupSettingsScreen(
                                        BackupUi(backupState, libraryState.notBackedUpCount, actions.backgroundLocation, serverUsage, storiesState.feed.counts),
                                        actions.backup,
                                        back = pop,
                                    )
                                }
                                Route.FreeUp -> {
                                    LaunchedEffect(Unit) { freeUp.refreshPlan() }
                                    FreeUpScreen(ui = storageUi(), actions = actions.storage, back = pop)
                                }
                                Route.Hidden -> HiddenScreen(server.library, settingsState.privateMedia.show, settings::setShowPrivateMedia, back = pop)
                                Route.Cameras -> CamerasRoute(
                                    server.library,
                                    // Loading and Unreachable keep the row's last count.
                                    onState = { if (it is CamerasState.Ready) camerasState = it },
                                    back = pop,
                                )
                                Route.SoundFolders -> SoundFoldersRoute(
                                    server.library,
                                    onState = { if (it is SoundFoldersState.Ready) soundFoldersState = it },
                                    back = pop,
                                )
                                is Route.Audio -> RecordingScreen(route.sha256, storiesState, server.library, back = pop)
                                Route.SearchSettings -> {
                                    LaunchedEffect(Unit) { localIndexes.refresh() }
                                    // Asked every 2 s while a run goes, every 30 s otherwise, while the screen shows.
                                    DisposableEffect(features?.indexing) {
                                        val watch = if (features?.indexing == true) serverIndexing.watch() else null
                                        onDispose { watch?.cancel() }
                                    }
                                    SearchSettingsScreen(
                                        searchPageSize = settingsState.searchPageSize,
                                        indexing = indexingState.takeIf { features?.indexing == true },
                                        phoneSearch = phoneSearchState.takeIf { features?.localIndexes == true },
                                        actions = SearchSettingsActions(
                                            back = pop,
                                            setSearchPageSize = settings::setSearchPageSize,
                                            startIndexing = serverIndexing::start,
                                            pauseIndexing = serverIndexing::pause,
                                            phoneSearch = PhoneSearchActions(
                                                set = localIndexes::set,
                                                setWifiOnly = localIndexes::setWifiOnly,
                                                updateNow = localIndexes::updateNow,
                                                pause = localIndexes::pause,
                                            ),
                                        ),
                                    )
                                }
                                is Route.Person -> {
                                    val controller = remember(route.id) { createPersonController(context, route.id, library) }
                                    DisposableEffect(controller) { onDispose { controller.close() } }
                                    val state by controller.state.collectAsStateWithLifecycle()
                                    LaunchedEffect(state.gone) { if (state.gone) pop() }
                                    val listKey = "person:${route.id}"
                                    val open: (MediaItem) -> Unit = { item ->
                                        pageLists[listKey] = state.items
                                        push(Route.Viewer(null, item.id, list = listKey))
                                    }
                                    if (state.person?.isGroup ?: route.id.startsWith("g")) {
                                        GroupScreen(
                                            state = state,
                                            actions = GroupActions(
                                                back = pop,
                                                setName = controller::setName,
                                                name = controller::rename,
                                                join = controller::join,
                                                openItem = open,
                                                retry = controller::load,
                                            ),
                                        )
                                    } else {
                                        PersonScreen(
                                            state = state,
                                            stories = storiesState,
                                            others = (peopleState.list.people + peopleState.list.groups).filter { it.id != state.id },
                                            actions = PersonActions(
                                                back = pop,
                                                setQuery = controller::setQuery,
                                                setSort = controller::setSort,
                                                openPerson = { push(Route.Person(it)) },
                                                openItem = open,
                                                rename = controller::rename,
                                                merge = controller::merge,
                                                forget = controller::forget,
                                                notThem = controller::notThem,
                                                retry = controller::load,
                                                notThemFaces = { faces -> controller.notThem(faces) },
                                                faces = faceActions,
                                            ),
                                        )
                                    }
                                }
                                is Route.Similar -> {
                                    val listKey = "similar:${route.itemId}"
                                    // As the page gave them until the database's are read (StoriesState.itemsById).
                                    val opened = pageLists[listKey]
                                    LaunchedEffect(listKey) { stories.want(opened.map { it.id }) }
                                    val items = opened.mapNotNull { item -> storiesState.itemsById[item.id] ?: item.takeUnless { storiesState.gone(it.id) } }
                                    SimilarScreen(
                                        items = items,
                                        onOpen = { push(Route.Viewer(null, it.id, list = listKey)) },
                                        onBack = pop,
                                    )
                                }
                                Route.Tags -> TagsScreen(storiesState, onOpenTag = { push(Route.Tag(it)) }, onBack = pop)
                                is Route.Tag -> TagScreen(
                                    tag = route.name,
                                    stories = storiesState,
                                    onOpenItem = { push(Route.Viewer(null, it.id, tag = route.name)) },
                                    onBack = pop,
                                    landing = landingHere,
                                )
                                is Route.Viewer -> {
                                    // The items as opened: shown until the database's are read (StoriesState.itemsById
                                    // holds only part of the library), so the viewer never comes out empty meanwhile.
                                    val kept = remember(route.encode()) { viewerItems(libraryState, storiesState, route, searchState, pageLists) }
                                    // Fixed when opened: tagging a photo or removing it from the storyline must not pull it out from under the viewer.
                                    var ids by rememberSaveable(route.encode()) { mutableStateOf(kept.map { it.id }) }
                                    // An Explore page's or the map's photos that weren't kept (the app was closed meanwhile)
                                    // are read from the database again (step 6c); until then nothing is shown.
                                    var read by remember(route.encode()) { mutableStateOf<List<MediaItem>?>(null) }
                                    val reading = route.scope != null && kept.isEmpty() && read == null
                                    LaunchedEffect(route.encode()) {
                                        val scope = route.scope ?: return@LaunchedEffect
                                        if (kept.isNotEmpty()) return@LaunchedEffect
                                        val items = scopeViewerItems(exploreReads, libraryState, storiesState, scope, route.moment)
                                        if (ids.isEmpty()) ids = items.map { it.id }
                                        read = items
                                    }
                                    val opened = read ?: kept
                                    val openedById = remember(opened) { opened.associateBy { it.id } }
                                    var shownId by remember(route.encode()) { mutableStateOf(route.itemId) }
                                    // The photo shown and its neighbours, read from the database as it is swiped.
                                    LaunchedEffect(ids, shownId) { stories.want(ids.around(shownId)) }
                                    if (reading) Box(Modifier.fillMaxSize().background(LibraryColors.Background)) else ViewerScreen(
                                        items = ids.mapNotNull { id -> storiesState.itemsById[id] ?: openedById[id]?.takeUnless { storiesState.gone(id) } },
                                        startId = route.itemId,
                                        stories = storiesState,
                                        actions = ViewerActions(
                                            back = pop,
                                            setInStoryline = stories::setInStoryline,
                                            setWriting = stories::setWriting,
                                            restoreWriting = stories::restoreWriting,
                                            toggleTag = { itemId, tag, add ->
                                                if (add) stories.addTag(listOf(itemId), tag) else stories.removeTag(listOf(itemId), tag)
                                            },
                                            showing = {
                                                shownId = it
                                                landing = Landing(entry.depth - 1, it)
                                            },
                                            setPrivate = actions.setPrivate,
                                            openMap = { push(Route.Map(it)) },
                                            share = actions.share,
                                            showInMoments = { itemId: String ->
                                                landing = null
                                                showInMoments = ShowInMoments(itemId)
                                                stack = listOf(Route.Journal)
                                            }.takeUnless { route.moment != null && route.scope == null },
                                            setZoom = stories::setZoom,
                                            findIn = findings::show,
                                            findings = FindingsActions(
                                                openPerson = { push(Route.Person(it)) },
                                                openSimilar = { item ->
                                                    val key = "similar:${findingsState.itemId}"
                                                    pageLists[key] = findingsState.similar
                                                    push(Route.Viewer(null, item.id, list = key))
                                                },
                                                moreSimilar = {
                                                    pageLists["similar:${findingsState.itemId}"] = findingsState.similar
                                                    push(Route.Similar(findingsState.itemId))
                                                },
                                            ),
                                            faces = faceActions,
                                        ),
                                        findings = findingsState,
                                        people = peopleState.list.people,
                                    )
                                }
                                is Route.Crop -> {
                                    LaunchedEffect(route.itemId) { stories.want(listOf(route.itemId)) }
                                    val item = storiesState.itemsById[route.itemId]
                                    if (item == null) {
                                        // Deleted from the device while open; else it is still being read.
                                        if (storiesState.gone(route.itemId)) LaunchedEffect(Unit) { pop() }
                                    } else {
                                        CropEditor(
                                            item = item,
                                            saved = storiesState.note(item.id)?.crops ?: PhotoCrops(),
                                            startKind = route.kind,
                                            onDone = { crops ->
                                                stories.setCrops(item.id, crops)
                                                pop()
                                            },
                                            onClose = pop,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

/** Material's shared X axis: the screen opened slides in from the side it lies on while the two crossfade. */
private fun sharedAxis(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    val slide = tween<IntOffset>(300, easing = FastOutSlowInEasing)
    return (slideInHorizontally(slide) { direction * it / 4 } + fadeIn(tween(210, delayMillis = 90))) togetherWith
        (slideOutHorizontally(slide) { -direction * it / 4 } + fadeOut(tween(90)))
}

/** Status and navigation bar icons that read on the library: light over dark screens, dark over light ones. */
@Composable
private fun SystemBars(activity: ComponentActivity?, dark: Boolean) {
    DisposableEffect(activity, dark) {
        val style = if (dark) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        }
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { activity?.enableEdgeToEdge() }
    }
}

/**
 * A sound recording's page ([AudioPage]), with the photos its moment in Moments took in, playing through the app's
 * [AudioPlayer]. A recording no longer shown (hidden since, or gone) goes back.
 */
@androidx.compose.runtime.Composable
private fun RecordingScreen(sha256: String, stories: com.chencohensolution.journal.stories.StoriesState, server: ServerLibrary, back: () -> Unit) {
    val recording = stories.sounds[sha256]
    if (recording == null) {
        LaunchedEffect(Unit) { back() }
        return
    }
    val player = LocalAudioPlayer.current
    val playback = player?.playback?.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val moment = remember(stories.feed, sha256) {
        stories.feed.entries.asSequence()
            .mapNotNull { (it as? com.chencohensolution.journal.stories.FeedEntry.Section)?.section }
            .flatMap { it.moments }
            .firstOrNull { it.recording?.sha256 == sha256 }
    }
    AudioPage(
        AudioPageState(recording, moment?.place, moment?.items.orEmpty().sortedBy { it.takenAtMillis }, playback?.value),
        AudioPageActions(
            play = { player?.play(recording) },
            pause = { player?.pause() },
            seek = { player?.seek(recording, it) },
            setSpeed = { player?.setSpeed(it) },
            back = back,
            dial = { number -> runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))) } },
            setCall = { contact, number ->
                scope.launch { server.setCall(sha256, contact.ifBlank { null }, number.ifBlank { null }) }
            },
        ),
    )
}
