package com.chencohensolution.journal.ui.library

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

// Settings › Backup › Copies on other disks, with a copy on a friend's server among them (the UI lab's made-up copies,
// BackupLab.kt). See docs/screens/settings.md.

@PreviewTest
@Preview(name = "dark", device = "spec:width=390dp,height=1900dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun Copies() = Screen { CopiesScreen(labCopies, CopiesActions(), back = {}) }

@PreviewTest
@Preview(name = "dark", device = "spec:width=390dp,height=2400dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun CopiesWithProblems() = Screen { CopiesScreen(labCopiesWithProblems, CopiesActions(), back = {}) }
