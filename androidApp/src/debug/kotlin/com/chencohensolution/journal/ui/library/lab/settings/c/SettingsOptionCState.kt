package com.chencohensolution.journal.ui.library

/**
 * How Moments is read a page at a time (Settings › Advanced › Loading Moments): [rowsPerPage] rows of Moments per
 * page, and the next page starts once the list is [loadAhead] rows from the end of what is read. Chosen as one of
 * three presets, or as any two values of the lists (Custom).
 */
internal data class SettingsOptionCState(
    val rowsPerPage: Int = Preset.BALANCED.rowsPerPage,
    val loadAhead: Int = Preset.BALANCED.loadAhead,
    /** Custom was picked, so its two values show even when they match a preset. */
    val custom: Boolean = false,
) {
    /** The preset these values are, or null when Custom. */
    val preset: Preset? get() = if (custom) null else Preset.entries.firstOrNull { it.rowsPerPage == rowsPerPage && it.loadAhead == loadAhead }

    enum class Preset(val rowsPerPage: Int, val loadAhead: Int) {
        LIGHT(15, 20),

        /** Today's values: FeedPager.PAGE and LOAD_AHEAD_ROWS. */
        BALANCED(30, 40),
        SMOOTH(90, 120),
    }

    companion object {
        val ROWS_PER_PAGE_STEPS: List<Int> = listOf(10, 15, 20, 30, 45, 60, 90, 120, 200)
        val LOAD_AHEAD_STEPS: List<Int> = listOf(10, 20, 30, 40, 60, 80, 120, 160)
    }
}
