package com.chencohensolution.journal.ui.library

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.chencohensolution.journal.settings.SettingsState
import com.chencohensolution.journal.settings.SimilarityThreshold

// Settings: the Journal's grouping, and the "Look alike" threshold changed from its default. See docs/previews.md.

@PreviewTest
@Preview(name = "light", device = "spec:width=390dp,height=844dp,dpi=420", showBackground = true)
@Preview(name = "dark", device = "spec:width=390dp,height=844dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun Settings() = Screen {
    SettingsScreen(SettingsState(similarity = SimilarityThreshold(percent = 72, defaultPercent = 85)), SettingsActions())
}
