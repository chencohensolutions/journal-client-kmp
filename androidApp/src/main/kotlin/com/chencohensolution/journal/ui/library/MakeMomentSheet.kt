package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.stories.ShowOn

/**
 * Make a moment (the picking bar, two or more photos picked): its name, its highlights (tap several; their order is the
 * order tapped) and which days of Moments show it. [onMake] gets them; the caller makes it
 * ([com.chencohensolution.journal.stories.StoriesController.makeMoment]) and opens the viewer on it. See
 * docs/screens/moments.md.
 */
@Composable
internal fun MakeMomentSheet(
    picked: List<MediaItem>,
    onMake: (name: String, highlightIds: List<String>, showOn: ShowOn) -> Unit,
    onDismiss: () -> Unit,
) {
    // Oldest first, as the moment keeps them.
    val photos = remember(picked) { picked.sortedBy { it.takenAtMillis } }
    var name by rememberSaveable { mutableStateOf("") }
    var highlights by rememberSaveable { mutableStateOf(listOf<String>()) }
    var showOn by rememberSaveable { mutableStateOf(ShowOn.FIRST) }
    val firstDay = photos.first().day
    val lastDay = photos.last().day
    PageSheet(onDismiss) {
        // Scrolls, so Make it stays reachable while the keyboard is up.
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.make_moment_title), color = LibraryColors.OnBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.make_moment_name)) }, singleLine = true)
            Text(stringResource(R.string.make_moment_highlights), color = LibraryColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(end = 8.dp)) {
                items(photos, key = { it.id }) { photo ->
                    val place = highlights.indexOf(photo.id)
                    Box(
                        Modifier.size(68.dp).clip(RoundedCornerShape(6.dp)).clickable {
                            highlights = if (place >= 0) highlights - photo.id else highlights + photo.id
                        },
                    ) {
                        SetThumb(photo, Modifier.fillMaxSize())
                        if (place >= 0) {
                            Box(Modifier.fillMaxSize().border(3.dp, Color.White, RoundedCornerShape(6.dp)))
                            HighlightMark(place + 1, Modifier.align(Alignment.TopStart).padding(4.dp))
                        }
                    }
                }
            }
            Text(stringResource(R.string.make_moment_highlights_hint), color = LibraryColors.Muted, fontSize = 12.sp)
            Text(stringResource(R.string.make_moment_show_on), color = LibraryColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().background(LibraryColors.Raised, RoundedCornerShape(10.dp)).padding(3.dp)) {
                val options = buildList {
                    add(ShowOn.FIRST to formatDate(firstDay.toLocalDate(), "EEEMMMd"))
                    if (lastDay != firstDay) {
                        add(ShowOn.LAST to formatDate(lastDay.toLocalDate(), "EEEMMMd"))
                        add(ShowOn.EACH to stringResource(R.string.make_moment_every_day))
                    }
                }
                options.forEach { (option, title) ->
                    val on = showOn == option
                    Box(
                        Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (on) LibraryColors.Accent else Color.Transparent).clickable { showOn = option },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(title, color = if (on) Color.White else LibraryColors.OnBackground, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
            }
            Text(
                stringResource(if (showOn == ShowOn.EACH) R.string.make_moment_hint_each else R.string.make_moment_hint_one),
                color = LibraryColors.Muted,
                fontSize = 12.sp,
            )
            val ready = name.isNotBlank()
            Box(
                Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (ready) LibraryColors.Accent else LibraryColors.Raised)
                    .clickable(enabled = ready) { onMake(name.trim(), highlights, showOn) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.make_moment_make),
                    color = if (ready) Color.White else LibraryColors.Muted,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
