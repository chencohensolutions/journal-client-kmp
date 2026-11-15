package com.chencohensolution.journal.ui

import androidx.compose.runtime.Composable
import com.chencohensolution.journal.ui.library.LibraryTheme

/**
 * The app's colors: the library palette, light or dark with the system (no wallpaper colors since Nov 16, 2026), so
 * enrollment and photo access look like the library. Screens that must be dark (the viewer) set their own
 * `LibraryTheme(dark = true)`.
 */
@Composable
fun JournalTheme(content: @Composable () -> Unit) = LibraryTheme(content = content)
