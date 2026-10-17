package com.chencohensolution.journal.stories

import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.settings.JournalSettings

/**
 * Story ids are the UTC creation time, "2026-10-17T12-52-22.257Z" (dashes instead of colons so the id is a valid
 * folder name everywhere). Each new id is at least 1 ms after the last one this device issued, so ids never repeat
 * on a device even if two stories are created in the same millisecond or the clock goes back.
 */
internal class StoryIds(private val settings: JournalSettings, private val nowMillis: () -> Long) {
    fun next(): String {
        val millis = maxOf(nowMillis(), (settings.lastStoryIdMillis ?: Long.MIN_VALUE) + 1)
        settings.lastStoryIdMillis = millis
        return formatStoryId(millis)
    }
}

internal fun formatStoryId(epochMillis: Long): String {
    val day = LocalDay.fromEpochDay(epochMillis.floorDiv(MILLIS_PER_DAY))
    val ofDay = epochMillis.mod(MILLIS_PER_DAY)
    val hours = ofDay / 3_600_000
    val minutes = ofDay / 60_000 % 60
    val seconds = ofDay / 1000 % 60
    val millis = ofDay % 1000
    return "${day}T${hours.pad(2)}-${minutes.pad(2)}-${seconds.pad(2)}.${millis.pad(3)}Z"
}

private const val MILLIS_PER_DAY = 86_400_000L

private fun Long.pad(length: Int) = toString().padStart(length, '0')
