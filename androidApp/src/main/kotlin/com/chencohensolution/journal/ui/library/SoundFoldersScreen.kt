package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.server.ServerLibrary
import com.chencohensolution.journal.server.ServerSoundFolder
import com.chencohensolution.journal.server.SoundFolderMark
import com.chencohensolution.journal.server.SoundFoldersController
import com.chencohensolution.journal.server.SoundFoldersState
import java.text.NumberFormat

/** A folder and where "Move to…" was asked to take it, waiting for Move or Cancel in its row. */
internal data class SoundFoldersChange(val folder: ServerSoundFolder, val mark: SoundFolderMark)

internal class SoundFoldersActions(
    /** A destination picked in a folder's "Move to…" menu: asks in the row first. */
    val moveTo: (ServerSoundFolder, SoundFolderMark) -> Unit = { _, _ -> },
    /** "Move": marks the folder being asked about. */
    val confirm: () -> Unit = {},
    val cancel: () -> Unit = {},
    val retry: () -> Unit = {},
    val back: () -> Unit = {},
)

/**
 * Settings › Sound folders, opened: a [SoundFoldersController] for this opening, and the move waiting for its answer in
 * its row (kept by folder id and mark across rotation). [onState] gets each state, for Settings' row.
 */
@Composable
internal fun SoundFoldersRoute(library: ServerLibrary, onState: (SoundFoldersState) -> Unit, back: () -> Unit) {
    val controller = remember(library) { SoundFoldersController(library) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(controller) { controller.load() }
    val state by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) { onState(state) }
    var asking by rememberSaveable { mutableStateOf<Pair<String, SoundFolderMark>?>(null) }
    val confirming = asking?.let { (id, mark) ->
        (state as? SoundFoldersState.Ready)?.folders?.firstOrNull { it.id == id }?.let { SoundFoldersChange(it, mark) }
    }
    SoundFoldersScreen(
        state,
        confirming,
        SoundFoldersActions(
            moveTo = { folder, mark -> asking = folder.id to mark },
            confirm = {
                confirming?.let { controller.setMark(it.folder, it.mark) }
                asking = null
            },
            cancel = { asking = null },
            retry = controller::retry,
            back = back,
        ),
    )
}

/**
 * Settings › Library › Sound folders: the folders sound recordings came from, grouped by where their recordings go,
 * Calls, Voice recordings, then Kept as folders, each with its files and a "Move to…" menu offering the other two.
 * Picking one asks in the row ([confirming]: "953 files will become Calls", Move or Cancel); while the server moves them
 * a band at the top says "Moving 1,520 files to Calls…" with a bar, and the folder already sits in its new group. See
 * docs/screens/settings.md.
 */
@Composable
internal fun SoundFoldersScreen(state: SoundFoldersState, confirming: SoundFoldersChange?, actions: SoundFoldersActions) {
    val insets = barsRoom()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            when (val folders = state) {
                SoundFoldersState.Loading -> Text(
                    stringResource(R.string.hidden_loading),
                    color = LibraryColors.Muted,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
                SoundFoldersState.Unreachable -> Column(Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.hidden_unreachable), color = LibraryColors.OnBackground, fontSize = 16.sp)
                    TextButton(onClick = actions.retry) { Text(stringResource(R.string.hidden_retry), color = LibraryColors.OnBackground) }
                }
                is SoundFoldersState.Ready -> {
                    folders.folders.firstOrNull { it.pending > 0 }?.let { MovingBand(it, folders.pending) }
                    if (folders.notChanged) {
                        Text(
                            stringResource(R.string.hidden_not_changed),
                            color = LibraryColors.Warning,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                        )
                    }
                    for ((mark, heading, about) in GROUPS) {
                        Group(
                            stringResource(heading),
                            stringResource(about),
                            folders.folders.filter { it.mark == mark },
                            confirming,
                            folders.saving,
                            actions,
                        )
                    }
                    Text(
                        stringResource(R.string.sound_folders_footer),
                        color = LibraryColors.Muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 10.dp),
                    )
                }
            }
        }
        TitleBar(stringResource(R.string.sound_folders_title), actions.back, Modifier.align(Alignment.TopCenter))
    }
}

/** The groups in order: where their recordings go, its heading and what it means. */
private val GROUPS = listOf(
    Triple(SoundFolderMark.CALL, R.string.sound_folders_calls, R.string.sound_folders_calls_about),
    Triple(SoundFolderMark.RECORDING, R.string.sound_folders_recordings, R.string.sound_folders_recordings_about),
    Triple(SoundFolderMark.NONE, R.string.sound_folders_kept, R.string.sound_folders_kept_about),
)

/** "Moving 1,520 files to Calls…" and a bar, above the groups. */
@Composable
private fun MovingBand(folder: ServerSoundFolder, pending: Int) {
    val format = NumberFormat.getIntegerInstance()
    val to = when (folder.mark) {
        SoundFolderMark.CALL -> stringResource(R.string.sound_folders_calls)
        SoundFolderMark.RECORDING -> stringResource(R.string.sound_folders_recordings)
        SoundFolderMark.NONE -> folder.shownName
    }
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(LibraryColors.Muted.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text(
            pluralStringResource(R.plurals.sound_folders_moving, pending, format.format(pending), to),
            color = LibraryColors.OnBackground,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(stringResource(R.string.sound_folders_moving_body), color = LibraryColors.Muted, fontSize = 13.sp)
        LinearProgressIndicator(
            progress = { 1f - pending.toFloat() / folder.files.coerceAtLeast(1) },
            color = LibraryColors.OnBackground,
            trackColor = LibraryColors.Muted.copy(alpha = 0.2f),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp),
        )
    }
}

/** One group: its heading and line, then a card of its folders, or "No folder yet". */
@Composable
private fun Group(
    heading: String,
    about: String,
    folders: List<ServerSoundFolder>,
    confirming: SoundFoldersChange?,
    saving: Boolean,
    actions: SoundFoldersActions,
) {
    Text(
        heading,
        color = LibraryColors.OnBackground,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 16.dp),
    )
    Text(about, color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 8.dp))
    if (folders.isEmpty()) {
        Text(
            stringResource(R.string.sound_folders_none),
            color = LibraryColors.Muted,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
        )
        return
    }
    SettingsCard {
        folders.forEachIndexed { i, folder ->
            if (i > 0) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
            FolderRow(folder, confirming?.takeIf { it.folder.id == folder.id }, saving, actions)
        }
    }
}

/** A folder: its name and files, "Move to…"; when a move is asked about, the question and Move or Cancel in the row. */
@Composable
private fun FolderRow(folder: ServerSoundFolder, confirming: SoundFoldersChange?, saving: Boolean, actions: SoundFoldersActions) {
    val format = NumberFormat.getIntegerInstance()
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(folder.shownName, color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(filesLine(folder), color = LibraryColors.Muted, fontSize = 13.sp)
                if (folder.pending > 0) {
                    Text(
                        pluralStringResource(R.plurals.sound_folders_row_moving, folder.pending, format.format(folder.pending)),
                        color = LibraryColors.OnBackground,
                        fontSize = 13.sp,
                    )
                }
            }
            Box {
                TextButton(onClick = { menu = true }, enabled = !saving && confirming == null) {
                    Text(stringResource(R.string.sound_folders_move_to), color = LibraryColors.OnBackground)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for ((mark, heading, _) in GROUPS) {
                        if (mark == folder.mark) continue
                        DropdownMenuItem(
                            text = { Text(stringResource(heading)) },
                            onClick = {
                                menu = false
                                actions.moveTo(folder, mark)
                            },
                        )
                    }
                }
            }
        }
        confirming?.let { change ->
            val files = format.format(folder.files)
            val question = when (change.mark) {
                SoundFolderMark.CALL -> pluralStringResource(R.plurals.sound_folders_to_calls, folder.files, files)
                SoundFolderMark.RECORDING -> pluralStringResource(R.plurals.sound_folders_to_recordings, folder.files, files)
                SoundFolderMark.NONE -> pluralStringResource(R.plurals.sound_folders_to_folder, folder.files, files, folder.shownName)
            }
            Column(
                Modifier
                    .padding(top = 8.dp, end = 8.dp)
                    .fillMaxWidth()
                    .background(LibraryColors.Muted.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                    .padding(start = 12.dp, end = 4.dp, top = 10.dp),
            ) {
                Text(question, color = LibraryColors.OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.sound_folders_confirm_body), color = LibraryColors.Muted, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = actions.cancel) { Text(stringResource(R.string.library_cancel), color = LibraryColors.Muted) }
                    TextButton(onClick = actions.confirm) { Text(stringResource(R.string.sound_folders_move), color = LibraryColors.OnBackground) }
                }
            }
        }
    }
}

/** "1,623 files · from your phones", "1,520 files · imported", "168 files · 61 imported". */
@Composable
private fun filesLine(folder: ServerSoundFolder): String {
    val format = NumberFormat.getIntegerInstance()
    val files = pluralStringResource(R.plurals.sound_folders_files, folder.files, format.format(folder.files))
    val from = when (folder.imported) {
        0 -> stringResource(R.string.sound_folders_from_phones)
        folder.files -> stringResource(R.string.sound_folders_all_imported)
        else -> stringResource(R.string.sound_folders_imported, format.format(folder.imported))
    }
    return "$files · $from"
}
