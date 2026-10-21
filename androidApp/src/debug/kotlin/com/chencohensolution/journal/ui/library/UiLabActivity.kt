package com.chencohensolution.journal.ui.library

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
                        option.content()
                    }
                    screen != null -> {
                        BackHandler { screenId = null }
                        LabList(
                            title = screen.title,
                            rows = screen.options.mapIndexed { i, it -> LabRow(it.title, if (i == 0) "Now in the app" else it.note) },
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
    CompositionLocalProvider(LocalThumbnailSource provides PlaceholderThumbnails) {
        LibraryTheme(content = content)
    }
}

/** A screen in the lab. [options] starts with the screen as the app has it, then the suggestions. */
internal class LabScreen(val id: String, val title: String, val options: List<LabOption>)

/** One way to draw a screen: the current one or a suggestion. [id] is what `ui-lab.sh` takes. */
internal class LabOption(val id: String, val title: String, val note: String = "", val content: @Composable () -> Unit)

private class LabRow(val title: String, val note: String)

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
            Column(Modifier.fillMaxWidth().clickable { onOpen(i) }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(row.title, color = LibraryColors.OnBackground, fontSize = 17.sp)
                if (row.note.isNotEmpty()) Text(row.note, color = LibraryColors.Muted, fontSize = 13.sp)
            }
        }
    }
}
