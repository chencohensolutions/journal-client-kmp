package com.chencohensolution.journal.library

import kotlin.time.TimeSource

/**
 * Timing lines for measuring the app with a large library (docs/plans/library-on-disk.md, phase 0): how long each
 * merge of the library and each rebuild of Moments takes. Debug builds set [log]; otherwise nothing is measured.
 */
object Measure {
    /** Where the lines go; null measures nothing. */
    var log: ((String) -> Unit)? = null

    /** Runs [block], and logs how long it took as "[what] in 120 ms". */
    internal inline fun <T> time(what: () -> String, block: () -> T): T {
        val log = log ?: return block()
        val start = TimeSource.Monotonic.markNow()
        return block().also { log("${what()} in ${start.elapsedNow().inWholeMilliseconds} ms") }
    }
}
