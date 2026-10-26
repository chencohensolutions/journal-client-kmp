package com.chencohensolution.journal.ui.library

/**
 * How Moments is read a page at a time (Settings › Advanced): [rowsPerPage] rows of Moments per page, and the next
 * page starts once the list is [loadAhead] rows from the end of what is read.
 */
internal data class SettingsOptionAState(
    val rowsPerPage: Int = DEFAULT_ROWS_PER_PAGE,
    val loadAhead: Int = DEFAULT_LOAD_AHEAD,
) {
    val isDefault: Boolean get() = rowsPerPage == DEFAULT_ROWS_PER_PAGE && loadAhead == DEFAULT_LOAD_AHEAD

    companion object {
        /** FeedPager.PAGE today. */
        const val DEFAULT_ROWS_PER_PAGE = 30

        /** LOAD_AHEAD_ROWS in JournalHomeScreen.kt today. */
        const val DEFAULT_LOAD_AHEAD = 40
        val ROWS_PER_PAGE_OPTIONS: List<Int> = listOf(15, 30, 60, 120)
        val LOAD_AHEAD_OPTIONS: List<Int> = listOf(10, 20, 40, 80)
    }
}
