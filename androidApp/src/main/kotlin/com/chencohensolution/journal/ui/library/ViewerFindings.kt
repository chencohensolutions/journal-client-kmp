package com.chencohensolution.journal.ui.library

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.people.FoundFace
import com.chencohensolution.journal.people.PhotoFindings
import kotlinx.coroutines.launch

// What the server found in a photo, in the viewer's info panel (docs/screens/viewer.md): the people in it, the text read
// in it, and the photos that look like it (the shared PhotoFindingsController).

internal class FindingsActions(
    /** A person's page; for a group with no name, "Who's this?". */
    val openPerson: (id: String) -> Unit = {},
    /** A face tapped: its sheet (This is Mom, Not Mom, Who is it?). */
    val tapFace: (FoundFace) -> Unit = {},
    /** The viewer on a similar photo, swiping through them. */
    val openSimilar: (MediaItem) -> Unit = {},
    /** Every similar photo, as a page. */
    val moreSimilar: () -> Unit = {},
)

/** The cards for [findings], in the info panel's colors; nothing when it found nothing. */
@Composable
internal fun FindingsCards(findings: PhotoFindings, actions: FindingsActions) {
    if (findings.people.isNotEmpty()) PeopleCard(findings.people, actions.tapFace)
    if (findings.text.isNotEmpty()) TextCard(findings.text)
    if (findings.similar.isNotEmpty()) SimilarCard(findings.similar, actions)
}

private val CardColor: Color @Composable get() = LibraryColors.OnInverse.copy(alpha = 0.06f)

@Composable
private fun FindingsCard(title: String, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(CardColor, RoundedCornerShape(16.dp)).padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = LibraryColors.OnInverse, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

/** "People in this photo": their faces with their names, "Who's this?" for a group. */
@Composable
private fun PeopleCard(people: List<FoundFace>, onOpen: (FoundFace) -> Unit) {
    FindingsCard(stringResource(R.string.findings_people)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            people.forEach { found ->
                val name = found.person?.name ?: found.face.personName
                Column(
                    Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onOpen(found) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        FaceAvatar(found.face, 52.dp)
                        if (name == null) {
                            Text(
                                "?",
                                color = LibraryColors.OnInverse,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.BottomEnd).size(20.dp).background(LibraryColors.Inverse, CircleShape).padding(top = 1.dp),
                                style = TextStyle(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                            )
                        }
                    }
                    Text(
                        name ?: stringResource(R.string.people_whos_this),
                        color = if (name == null) LibraryColors.OnInverseMuted else LibraryColors.OnInverse,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** "Text in this photo": the lines as read (Hebrew right to left), with Copy for all of them. */
@Composable
private fun TextCard(lines: List<String>) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val all = lines.joinToString("\n")
    FindingsCard(
        stringResource(R.string.findings_text),
        trailing = {
            TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, all))) } }) {
                Icon(LibraryIcons.Copy, null, tint = LibraryColors.Accent, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.findings_copy), color = LibraryColors.Accent, modifier = Modifier.padding(start = 6.dp))
            }
        },
    ) {
        Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lines.forEach { line ->
                Text(
                    line,
                    color = LibraryColors.OnInverse,
                    fontSize = 15.sp,
                    style = TextStyle(textDirection = TextDirection.Content),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** "Looks like this": a row of the most alike photos, and More for all of them. */
@Composable
private fun SimilarCard(similar: List<MediaItem>, actions: FindingsActions) {
    FindingsCard(
        stringResource(R.string.findings_similar),
        trailing = {
            TextButton(onClick = actions.moreSimilar) {
                Text(stringResource(R.string.findings_more), color = LibraryColors.Accent)
                Icon(LibraryIcons.ChevronRight, null, tint = LibraryColors.Accent, modifier = Modifier.size(16.dp))
            }
        },
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            similar.take(SIMILAR_SHOWN).forEach { item ->
                CroppedThumbnail(item, Modifier.size(76.dp).clip(RoundedCornerShape(10.dp)).clickable { actions.openSimilar(item) })
            }
        }
    }
}

/** Similar photos in the panel's row; More shows the rest. */
private const val SIMILAR_SHOWN = 8
