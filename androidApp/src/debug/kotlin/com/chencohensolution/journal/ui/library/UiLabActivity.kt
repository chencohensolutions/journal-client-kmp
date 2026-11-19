package com.chencohensolution.journal.ui.library

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.ui.JournalTheme

/**
 * The UI lab (debug builds only): a library of the app's screens and of suggested designs for them, with made-up
 * data ([PreviewData]) and placeholder pictures, without the server, the photo library or enrollment. The first page
 * lists the screens ([labScreens]); a screen lists its options, the first being what the app has now. Taps that would
 * reach a controller do nothing. `./scripts/ui-lab.sh <screen> [option]` opens one directly. With Android Studio's
 * Live Edit, a saved change to a composable shows here in about a second. See docs/previews.md.
 */
class UiLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val askedScreen = intent.getStringExtra("screen")?.takeIf { id -> labScreens.any { it.id == id } }
        val askedOption = intent.getStringExtra("option")
        setContent {
            LabTheme {
                var screenId by rememberSaveable { mutableStateOf(askedScreen) }
                var optionId by rememberSaveable { mutableStateOf(askedOption) }
                val screen = labScreens.firstOrNull { it.id == screenId }
                val option = screen?.options?.firstOrNull { it.id == optionId }
                when {
                    option != null -> {
                        BackHandler { optionId = null }
                        Box(Modifier.fillMaxSize()) {
                            option.content()
                            LabOptionBadge(option.id, Modifier.align(Alignment.TopEnd))
                        }
                    }
                    screen != null -> {
                        BackHandler { screenId = null }
                        LabList(
                            title = screen.title,
                            rows = screen.options.mapIndexed { i, it -> LabRow(it.title, if (i == 0) "Now in the app" else it.note, labBadge(it.id)) },
                            onOpen = { optionId = screen.options[it].id },
                        )
                    }
                    else -> LabList(
                        title = "UI lab",
                        rows = labScreens.map { LabRow(it.title, optionCount(it.options.size)) },
                        onOpen = {
                            screenId = labScreens[it].id
                            optionId = null
                        },
                    )
                }
            }
        }
    }
}

/** The app's own themes (MainActivity, LibraryApp), with placeholder pictures instead of the photo library. */
@Composable
private fun LabTheme(content: @Composable () -> Unit) = JournalTheme {
    CompositionLocalProvider(LocalThumbnailSource provides PlaceholderThumbnails, LocalFaceSource provides PlaceholderFaces) {
        LibraryTheme(content = content)
    }
}

/** A screen in the lab. [options] starts with the screen as the app has it, then the suggestions. */
internal class LabScreen(val id: String, val title: String, val options: List<LabOption>)

/** One way to draw a screen: the current one or a suggestion. [id] is what `ui-lab.sh` takes. */
internal class LabOption(val id: String, val title: String, val note: String = "", val content: @Composable () -> Unit)

private class LabRow(val title: String, val note: String, val badge: String? = null)

/**
 * The option's letter as the lab shows it: "A2" for option a2, "NOW" for the app's current screen. Asked Nov 19, 2026:
 * which option a screen is should be clear at a glance, and in a screenshot.
 */
private fun labBadge(id: String) = if (id == "current") "NOW" else id.uppercase()

private val BadgeColor = Color(0xFFDB2777)

/** The option's letter in the top corner of an option, solid, so screenshots carry it too. */
@Composable
private fun LabOptionBadge(id: String, modifier: Modifier = Modifier) {
    Text(
        labBadge(id),
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.Black,
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 4.dp, end = 10.dp)
            .background(BadgeColor, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

private fun optionCount(n: Int) = if (n == 1) "Current only" else "Current + ${n - 1} suggestion${if (n > 2) "s" else ""}"

@Composable
private fun LabList(title: String, rows: List<LabRow>, onOpen: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().background(LibraryColors.Background).safeDrawingPadding()) {
        item {
            Text(
                title,
                color = LibraryColors.OnBackground,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        itemsIndexed(rows) { i, row ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(i) }.padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.badge?.let { badge ->
                    Text(
                        badge,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp).background(BadgeColor, RoundedCornerShape(50)).widthIn(min = 40.dp).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Column {
                    Text(row.title, color = LibraryColors.OnBackground, fontSize = 17.sp)
                    if (row.note.isNotEmpty()) Text(row.note, color = LibraryColors.Muted, fontSize = 13.sp)
                }
            }
        }
    }
}
