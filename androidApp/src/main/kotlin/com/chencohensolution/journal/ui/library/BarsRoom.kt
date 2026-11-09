package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/*
 * The system bars' room, kept while they are hidden. The viewer hides the status bar and the gesture bar (immersive);
 * a screen under it measured by the bars as they show (WindowInsets.systemBars, statusBarsPadding()) was laid out
 * without them while coming back from the viewer, then moved down when they showed again a few frames later: the
 * Moments feed dropped down a bit on closing the viewer (Nov 9, 2026). Measured by their room instead, nothing moves.
 */

/** The status bar's room, kept while it is hidden. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Modifier.statusBarRoom() = windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)

/** The same for the gesture bar at the bottom. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Modifier.navigationBarRoom() = windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)

/** Both bars' room as padding, for a list's content padding. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun barsRoom(): PaddingValues = WindowInsets.systemBarsIgnoringVisibility.asPaddingValues()

/** The gesture bar's room as insets, kept while it is hidden. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun navigationBarInsets(): WindowInsets = WindowInsets.navigationBarsIgnoringVisibility
