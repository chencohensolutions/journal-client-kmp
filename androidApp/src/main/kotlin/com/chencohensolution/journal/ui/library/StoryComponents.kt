package com.chencohensolution.journal.ui.library

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.stories.StoryView
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import java.time.LocalDate
import kotlin.math.abs

/** "Oct 8", "Oct 8 – 16", "Sep 28 – Oct 3", with the year when it isn't this year. */
@Composable
internal fun dateRange(start: LocalDay, end: LocalDay): String {
    val context = LocalContext.current
    val dayMillis = 86_400_000L
    val thisYear = LocalDate.now().year
    var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    flags = flags or if (start.year == thisYear && end.year == thisYear) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR
    // An end at midnight counts as the day before, so end on the midnight after the last day.
    return DateUtils.formatDateRange(
        context,
        java.util.Formatter(StringBuilder(), LocalLocale.current.platformLocale),
        start.toEpochDay() * dayMillis,
        (end.toEpochDay() + 1) * dayMillis,
        flags,
        "UTC",
    ).toString()
}

/**
 * "Oct 8 – 16 · 9 days · 142 photos", from the story's photos (stories have no dates of their own). The day count
 * shows only for trip-like stories of more than one day.
 */
@Composable
internal fun storySummary(story: StoryView): String {
    val parts = mutableListOf<String>()
    if (story.firstDay != null && story.lastDay != null) parts += dateRange(story.firstDay!!, story.lastDay!!)
    if (story.isShortSpan && story.dayCount > 1) parts += pluralStringResource(R.plurals.library_story_days, story.dayCount, story.dayCount)
    parts += pluralStringResource(R.plurals.library_story_items, story.itemCount, story.itemCount)
    return parts.joinToString(" · ")
}

/** Calendar-style first day of a story: "OCT" over "8". */
@Composable
internal fun DateBlock(day: LocalDay, modifier: Modifier = Modifier) {
    val date = day.toLocalDate()
    Column(
        modifier
            .width(52.dp)
            .background(LibraryColors.Paper.copy(alpha = 0.94f), RoundedCornerShape(12.dp))
            .padding(top = 6.dp, bottom = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(formatDate(date, "MMM").uppercase(), color = LibraryColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Text(date.dayOfMonth.toString(), color = LibraryColors.PaperInk, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
    }
}

/** The story's title, or for an untitled one its dates ("Oct 8 – 16"). */
@Composable
internal fun storyName(story: StoryView): String = when {
    story.hasTitle -> story.title
    story.firstDay != null && story.lastDay != null -> dateRange(story.firstDay!!, story.lastDay!!)
    else -> stringResource(R.string.library_untitled_story)
}

/** Asks for a story's title: when creating one, and when renaming. */
@Composable
internal fun StoryTitleDialog(
    title: String,
    initial: String = "",
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.library_story_title_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            // Titles are optional: a blank one is saved as none.
            TextButton(onClick = { onConfirm(text) }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        },
    )
}
