package com.chencohensolution.journal.ui.library

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.chencohensolution.journal.stories.BurstSuggestion

// Suggestion cards: photos picked because they look alike (hours apart), and a quick burst picked by time.

private val lookAlike = BurstSuggestion(List(3) { PreviewData.photo(2, hour = 10 + it * 3) }, byLooks = true)
private val burst = PreviewData.photo(2, hour = 18).let { first ->
    BurstSuggestion(List(4) { first.copy(id = "burst-$it", takenAtMillis = first.takenAtMillis + it * 3_000L) })
}

@PreviewTest
@Preview(name = "light", device = "spec:width=390dp,height=420dp,dpi=420", showBackground = true)
@Preview(name = "dark", device = "spec:width=390dp,height=420dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SuggestionCards() = Screen {
    Column(Modifier.background(LibraryColors.Background).padding(vertical = 12.dp)) {
        BurstSuggestionCard(lookAlike, onGroup = {}, onDismiss = {})
        BurstSuggestionCard(burst, onGroup = {}, onDismiss = {})
    }
}
