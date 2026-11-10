package com.chencohensolution.journal.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chencohensolution.journal.R
import com.chencohensolution.journal.stories.NoteVersion
import com.chencohensolution.journal.stories.PhotoNote
import com.chencohensolution.journal.stories.changedParts
import java.time.Instant
import java.time.ZoneId

/**
 * A photo's writing: its paragraph (the title field went on Nov 10, 2026; saving clears a title written before), and its History (earlier versions, each with what the next one changed,
 * and Restore). Cancel with changes asks first. See docs/screens/viewer.md.
 */
@Composable
internal fun NoteEditor(
    note: PhotoNote?,
    onSave: (title: String, text: String) -> Unit,
    onRestore: (NoteVersion) -> Unit,
    onDismiss: () -> Unit,
) {
    // No title any more (asked Nov 10, 2026): saving clears one written before (History keeps it).
    var title by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf(note?.caption.orEmpty()) }
    // null: writing; -1: the History list; otherwise the version shown, by its index in note.history.
    var historyAt by rememberSaveable { mutableStateOf<Int?>(null) }
    var askingDiscard by remember { mutableStateOf(false) }
    val changed = text.trim() != note?.caption.orEmpty()
    val close = { if (changed) askingDiscard = true else onDismiss() }
    val history = note?.history.orEmpty()

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = LibraryColors.Background) {
            Column(Modifier.systemBarsPadding().imePadding()) {
                when (val at = historyAt) {
                    null -> Writing(
                        title, text, note, history,
                        onTitle = { title = it },
                        onText = { text = it },
                        onCancel = close,
                        onSave = { onSave(title, text) },
                        onHistory = { historyAt = -1 },
                    )
                    -1 -> {
                        BackHandler { historyAt = null }
                        HistoryList(history, onBack = { historyAt = null }, onOpen = { historyAt = it })
                    }
                    else -> {
                        BackHandler { historyAt = -1 }
                        val version = history[at]
                        // What came after it: the next version, or what is written now for the newest.
                        val later = history.getOrNull(at + 1)?.let { it.title to it.text } ?: (note?.title.orEmpty() to note?.caption.orEmpty())
                        VersionPage(version, later, onBack = { historyAt = -1 }, onRestore = { onRestore(version) })
                    }
                }
            }
        }
    }
    if (askingDiscard) {
        AlertDialog(
            onDismissRequest = { askingDiscard = false },
            title = { Text(stringResource(R.string.note_discard_title)) },
            confirmButton = {
                TextButton(onClick = {
                    askingDiscard = false
                    onDismiss()
                }) { Text(stringResource(R.string.note_discard)) }
            },
            dismissButton = { TextButton(onClick = { askingDiscard = false }) { Text(stringResource(R.string.note_keep_editing)) } },
        )
    }
}

@Composable
private fun ColumnScope.Writing(
    title: String,
    text: String,
    note: PhotoNote?,
    history: List<NoteVersion>,
    onTitle: (String) -> Unit,
    onText: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onHistory: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.library_cancel), color = LibraryColors.OnBackground) }
        Text(stringResource(R.string.note_writing), color = LibraryColors.OnBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = onSave) { Text(stringResource(R.string.library_save), color = LibraryColors.Warning, fontWeight = FontWeight.Bold) }
    }
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        placeholder = { Text(stringResource(R.string.library_note_prompt)) },
        textStyle = TextStyle(color = LibraryColors.OnBackground, fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 27.sp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        // Only the photo's paragraph (asked Nov 10, 2026: no title field; saving clears a title written before).
        modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp).focusRequester(focus),
    )
    if (history.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onHistory).padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(LibraryIcons.Clock, null, tint = LibraryColors.Muted, modifier = Modifier.size(16.dp))
            Text(
                pluralStringResource(R.plurals.note_edited, history.size, history.size, savedLabel(note?.writtenAt ?: note?.updatedAt)),
                color = LibraryColors.Muted,
                fontSize = 14.sp,
            )
        }
    }
}

/** Earlier versions, newest first: when, the title, and the start of the paragraph. */
@Composable
private fun HistoryList(history: List<NoteVersion>, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    TitleRow(stringResource(R.string.note_history), onBack)
    LazyColumn(Modifier.fillMaxSize()) {
        items(history.indices.reversed().toList()) { index ->
            val version = history[index]
            Column(Modifier.fillMaxWidth().clickable { onOpen(index) }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(savedLabel(version.savedAt), color = LibraryColors.Muted, fontSize = 13.sp)
                Text(
                    version.title.ifBlank { stringResource(R.string.note_no_title) },
                    color = if (version.title.isBlank()) LibraryColors.Muted else LibraryColors.OnBackground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (version.text.isNotBlank()) {
                    Text(version.text, color = LibraryColors.OnBackground, fontFamily = FontFamily.Serif, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
        }
    }
}

/** One version in full, with what the version after it no longer has highlighted, and Restore. */
@Composable
private fun ColumnScope.VersionPage(version: NoteVersion, later: Pair<String, String>, onBack: () -> Unit, onRestore: () -> Unit) {
    TitleRow(savedLabel(version.savedAt), onBack)
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        if (version.title.isBlank()) {
            Text(stringResource(R.string.note_no_title), color = LibraryColors.Muted, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Text(highlighted(version.title, later.first), color = LibraryColors.OnBackground, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            highlighted(version.text, later.second),
            color = LibraryColors.OnBackground,
            fontFamily = FontFamily.Serif,
            fontSize = 18.sp,
            lineHeight = 27.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    Button(
        onClick = onRestore,
        colors = ButtonDefaults.buttonColors(containerColor = LibraryColors.Inverse, contentColor = LibraryColors.OnInverse),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) { Text(stringResource(R.string.note_restore)) }
}

@Composable
private fun TitleRow(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(LibraryIcons.Back, stringResource(R.string.library_back), tint = LibraryColors.OnBackground) }
        Text(title, color = LibraryColors.OnBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/** [earlier] with the parts [later] no longer has on a warm background. */
@Composable
private fun highlighted(earlier: String, later: String) = buildAnnotatedString {
    val mark = SpanStyle(background = LibraryColors.Warning.copy(alpha = 0.25f))
    changedParts(earlier, later).forEach { part ->
        if (part.changed) withStyle(mark) { append(part.text) } else append(part.text)
    }
}

/** "Oct 21, 14:02" this year, "Oct 21, 2025, 14:02" before. */
private fun savedLabel(savedAt: String?): String {
    val instant = savedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return ""
    val time = instant.atZone(ZoneId.systemDefault())
    return formatDate(time, if (time.year == java.time.LocalDate.now().year) "MMMdjmm" else "yMMMdjmm")
}
