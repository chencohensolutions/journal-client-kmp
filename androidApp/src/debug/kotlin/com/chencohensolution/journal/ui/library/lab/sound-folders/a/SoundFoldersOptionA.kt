package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.server.ServerSoundFolder
import com.chencohensolution.journal.server.SoundFolderMark
import com.chencohensolution.journal.server.SoundFoldersState
import java.text.NumberFormat

/**
 * Settings › Library › Sound folders, option a: one card of every folder sound recordings came from, the most files
 * first, each with its files ("1,520 files · imported") and three choices under it, Calls, Voice recordings and Keep as
 * folder. Tapping another asks first ("1,520 files will become Calls"); while the server moves them the row says
 * "Moving 1,520…" with a bar.
 */
@Composable
internal fun SoundFoldersScreenA(state: SoundFoldersOptionAState, actions: SoundFoldersOptionAActions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            Text(
                stringResource(R.string.sound_folders_a_intro),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 12.dp),
            )
            when (val folders = state.folders) {
                SoundFoldersState.Loading -> Text(
                    stringResource(R.string.hidden_loading),
                    color = LibraryColors.Muted,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                SoundFoldersState.Unreachable -> Column(Modifier.padding(horizontal = 32.dp)) {
                    Text(stringResource(R.string.hidden_unreachable), color = LibraryColors.OnBackground, fontSize = 16.sp)
                    TextButton(onClick = actions.retry) { Text(stringResource(R.string.hidden_retry), color = LibraryColors.OnBackground) }
                }
                is SoundFoldersState.Ready -> {
                    if (folders.notChanged) {
                        Text(
                            stringResource(R.string.hidden_not_changed),
                            color = LibraryColors.Warning,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                        )
                    }
                    SettingsCard {
                        folders.folders.forEachIndexed { i, folder ->
                            if (i > 0) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                            FolderRow(folder, folders.saving, actions)
                        }
                    }
                    Text(
                        stringResource(R.string.sound_folders_a_footer),
                        color = LibraryColors.Muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 10.dp),
                    )
                }
            }
        }
        TitleBar(stringResource(R.string.sound_folders_a_title), actions.back, Modifier.align(Alignment.TopCenter))
    }
    state.confirming?.let { change ->
        val files = NumberFormat.getIntegerInstance().format(change.folder.files)
        val title = when (change.mark) {
            SoundFolderMark.CALL -> pluralStringResource(R.plurals.sound_folders_a_to_calls, change.folder.files, files)
            SoundFolderMark.RECORDING -> pluralStringResource(R.plurals.sound_folders_a_to_recordings, change.folder.files, files)
            SoundFolderMark.NONE -> pluralStringResource(R.plurals.sound_folders_a_to_folder, change.folder.files, files, change.folder.shownName)
        }
        AlertDialog(
            onDismissRequest = actions.cancel,
            title = { Text(title) },
            text = { Text(stringResource(R.string.sound_folders_a_confirm_body)) },
            confirmButton = { TextButton(onClick = actions.confirm) { Text(stringResource(R.string.sound_folders_a_move)) } },
            dismissButton = { TextButton(onClick = actions.cancel) { Text(stringResource(R.string.library_cancel)) } },
        )
    }
}

/** A folder: its name, "1,623 files · from your phones", the three choices; while moving, how many are left. */
@Composable
private fun FolderRow(folder: ServerSoundFolder, saving: Boolean, actions: SoundFoldersOptionAActions) {
    val format = NumberFormat.getIntegerInstance()
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
        Text(folder.shownName, color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(filesLine(folder), color = LibraryColors.Muted, fontSize = 13.sp)
        FlowRow(
            Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((mark, label) in listOf(
                SoundFolderMark.CALL to R.string.sound_folders_a_calls,
                SoundFolderMark.RECORDING to R.string.sound_folders_a_recordings,
                SoundFolderMark.NONE to R.string.sound_folders_a_folder,
            )) {
                FilterBarChip(stringResource(label), null, on = folder.mark == mark, small = true) {
                    if (!saving && folder.mark != mark) actions.choose(folder, mark)
                }
            }
        }
        if (folder.pending > 0) {
            Text(
                pluralStringResource(R.plurals.sound_folders_a_moving, folder.pending, format.format(folder.pending)),
                color = LibraryColors.OnBackground,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            LinearProgressIndicator(
                progress = { 1f - folder.pending.toFloat() / folder.files.coerceAtLeast(1) },
                color = LibraryColors.OnBackground,
                trackColor = LibraryColors.Muted.copy(alpha = 0.2f),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp),
            )
        }
    }
}

/** "1,623 files · from your phones", "1,520 files · imported", "168 files · 61 imported". */
@Composable
private fun filesLine(folder: ServerSoundFolder): String {
    val format = NumberFormat.getIntegerInstance()
    val files = pluralStringResource(R.plurals.sound_folders_a_files, folder.files, format.format(folder.files))
    val from = when (folder.imported) {
        0 -> stringResource(R.string.sound_folders_a_from_phones)
        folder.files -> stringResource(R.string.sound_folders_a_all_imported)
        else -> stringResource(R.string.sound_folders_a_imported, format.format(folder.imported))
    }
    return "$files · $from"
}
