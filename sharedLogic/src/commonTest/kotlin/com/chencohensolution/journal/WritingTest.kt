package com.chencohensolution.journal

import com.chencohensolution.journal.stories.DiffPart
import com.chencohensolution.journal.stories.NoteVersion
import com.chencohensolution.journal.stories.PhotoNote
import com.chencohensolution.journal.stories.changedParts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WritingTest {
    private val blank = PhotoNote("p1", "p1.jpg", 0, updatedAt = "2026-10-21T09:00:00Z")

    @Test
    fun aSaveKeepsWhatWasWrittenBeforeAsAVersion() {
        val first = blank.withWriting(" Beach ", " First swim \n", "2026-10-21T10:00:00Z")
        assertEquals("Beach", first.title)
        assertEquals("First swim", first.caption)
        assertTrue(first.history.isEmpty())

        val second = first.withWriting("Beach day", "First swim of the year", "2026-10-21T11:00:00Z")
        assertEquals(listOf(NoteVersion("Beach", "First swim", "2026-10-21T10:00:00Z")), second.history)
        assertEquals("2026-10-21T11:00:00Z", second.writtenAt)
    }

    @Test
    fun savesWithinTenMinutesAreOneVersion() {
        val first = blank.withWriting("Beach", "Frist swim", "2026-10-21T10:00:00Z")
        val fixed = first.withWriting("Beach", "First swim", "2026-10-21T10:09:00Z")
        assertTrue(fixed.history.isEmpty())
        assertSame(fixed, fixed.withWriting("Beach", "First swim ", "2026-10-21T12:00:00Z"))
    }

    @Test
    fun restoringKeepsWhatItReplaces() {
        val first = blank.withWriting("A", "one", "2026-10-21T10:00:00Z")
        val second = first.withWriting("B", "two", "2026-10-21T11:00:00Z")
        val restored = second.withWriting("A", "one", "2026-10-21T11:01:00Z", keepPrevious = true)
        assertEquals(listOf("A", "B"), restored.history.map { it.title })
        assertEquals("A", restored.title)
    }

    @Test
    fun aNoteFromBeforeTitlesKeepsItsTextWithItsTime() {
        val old = blank.copy(caption = "Old caption")
        val edited = old.withWriting("", "New caption", "2026-10-21T09:05:00Z")
        assertEquals(listOf(NoteVersion("", "Old caption", "2026-10-21T09:00:00Z")), edited.history)
    }

    @Test
    fun changedPartsMarksWhatTheNextVersionDropped() {
        assertEquals(
            listOf(DiffPart("We swam ", false), DiffPart("twice ", true), DiffPart("at dawn", false)),
            changedParts("We swam twice at dawn", "We swam at dawn again"),
        )
        assertEquals(listOf(DiffPart("same text", false)), changedParts("same text", "same text"))
    }
}
