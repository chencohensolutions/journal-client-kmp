package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.local.PhoneDownload
import com.chencohensolution.journal.local.PhoneIndex
import com.chencohensolution.journal.local.PhoneIndexKind
import com.chencohensolution.journal.local.PhoneSearchState
import com.chencohensolution.journal.people.Face
import com.chencohensolution.journal.people.FaceBox
import com.chencohensolution.journal.people.FoundFace
import com.chencohensolution.journal.people.PageSort
import com.chencohensolution.journal.people.PeopleList
import com.chencohensolution.journal.people.PeopleState
import com.chencohensolution.journal.people.PeopleStatus
import com.chencohensolution.journal.people.Person
import com.chencohensolution.journal.people.PersonState
import com.chencohensolution.journal.people.PersonStatus
import com.chencohensolution.journal.people.PhotoFindings
import com.chencohensolution.journal.people.Together

// Made-up data for People, a person's page, "Who's this?", the viewer's findings and Settings › On this phone, for the
// screenshot tests and the UI lab. Faces are drawn by PlaceholderFaces; photos of a person by the placeholder pictures
// for files named "face_<person>_<n>", with the face at the box below.

/** Where the placeholder draws a face in a photo: the middle of its circle and its width, as fractions of the picture. */
internal const val FACE_CENTER_X = 0.5f
internal const val FACE_CENTER_Y = 0.38f
internal const val FACE_WIDTH = 0.3f

internal object PeopleLabData {
    /** A photo of [person], [daysAgo] back. */
    private fun photoOf(person: String, n: Int, daysAgo: Int, ratio: Float = 3f / 4f) =
        PreviewData.photo(daysAgo, hour = 10 + n % 9, ratio = ratio).copy(fileName = "face_${person}_$n.jpg", sha256 = "%064x".format(person.hashCode().toLong() * 1000 + n))

    private fun faceIn(item: MediaItem, personId: String?, name: String? = null): Face {
        val ratio = item.width.toFloat() / item.height
        val height = FACE_WIDTH * ratio
        return Face("${item.sha256}:0", item.sha256!!, FaceBox(FACE_CENTER_X - FACE_WIDTH / 2, FACE_CENTER_Y - height / 2, FACE_WIDTH, height), personId, name)
    }

    private fun named(id: String, name: String, photos: Int) =
        Person(id, name, photos, photos + 10, faceIn(photoOf(name.lowercase(), 0, 3), id, name))

    private fun group(id: String, photos: Int) = Person(id, null, photos, photos, faceIn(photoOf(id, 0, 3), id))

    val mom = named("p1", "Mom", 812)
    private val dana = named("p2", "Dana", 640)
    private val avi = named("p3", "Avi", 301)
    private val noa = named("p4", "Noa", 122)
    private val ron = named("p5", "Ron", 98)
    private val yael = named("p6", "Yael", 64)
    private val grandpa = named("p7", "Grandpa", 41)

    val groups = listOf(54, 31, 20, 12, 9, 7, 5, 4, 3, 3, 3).mapIndexed { i, photos -> group("g${i + 1}", photos) }

    private val named = listOf(mom, dana, avi, noa, ron, yael, grandpa)

    val people = PeopleState(PeopleStatus.READY, PeopleList(named, groups, read = 14_200, total = 14_200))

    /** The server still reading files for faces. */
    val peopleReading = PeopleState(PeopleStatus.READY, PeopleList(named.take(3), groups.take(3), read = 12_480, total = 14_200))

    /** Faces found, no one named yet. */
    val peopleUnnamed = PeopleState(PeopleStatus.READY, PeopleList(emptyList(), groups, read = 14_200, total = 14_200))

    private val momPhotos = (0 until 14).map { photoOf("mom", it, daysAgo = it / 3 * 4, ratio = if (it % 3 == 0) 4f / 3f else 3f / 4f) }
        .mapIndexed { i, item -> item.copy(aesthetic = 3f + (i * 7 % 6)) }

    val momPage = PersonState(
        id = mom.id,
        person = mom,
        status = PersonStatus.READY,
        items = momPhotos,
        faces = momPhotos.associate { it.id to faceIn(it, mom.id, "Mom") },
        together = listOf(Together(dana, 214), Together(avi, 96), Together(grandpa, 33), Together(groups[0], 18)),
    )

    val momSearched = momPage.copy(items = momPhotos.take(5), query = "beach", sort = PageSort.BEST, together = emptyList())

    val others = (named + groups).filter { it.id != mom.id }

    val notMomPhoto = momPhotos[2]

    private val groupPhotos = (0 until 18).map { photoOf("g1", it, daysAgo = it * 9) }

    val groupPage = PersonState(
        id = groups[0].id,
        person = groups[0],
        status = PersonStatus.READY,
        items = groupPhotos,
        faces = groupPhotos.associate { it.id to faceIn(it, groups[0].id) },
    )
    val groupTyping = groupPage.copy(name = "Mo", matches = listOf(mom))

    /** The viewer's findings on a family photo with a receipt. */
    val findings = PhotoFindings(
        itemId = labInfoItem.id,
        people = listOf(FoundFace(mom.cover, mom), FoundFace(dana.cover, dana), FoundFace(groups[1].cover, groups[1])),
        text = listOf("קבלה מס' 4471", "קפה נמל יפו", "Total 128.40 ₪"),
        similar = (0 until 6).map { PreviewData.photo(14, hour = 11, ratio = if (it % 2 == 0) 4f / 3f else 3f / 4f) },
    )

    private const val MB = 1_000_000L

    private val indexes = listOf(
        PhoneIndex(PhoneIndexKind.WORDS, on = true, bytes = 440 * MB, modelBytes = 290 * MB, keptBytes = 730 * MB),
        PhoneIndex(PhoneIndexKind.PEOPLE, on = true, bytes = 88 * MB, keptBytes = 88 * MB),
        PhoneIndex(PhoneIndexKind.TEXT, on = true, bytes = 21 * MB, keptBytes = 21 * MB),
        PhoneIndex(PhoneIndexKind.SIMILAR, on = false, bytes = 152 * MB),
        PhoneIndex(PhoneIndexKind.AESTHETIC, on = false, bytes = 4 * MB),
    )

    /** Kept and up to date, 12 new on the server since. */
    val phoneSearch = PhoneSearchState(
        indexes,
        updatedAtMillis = System.currentTimeMillis() - 2 * 3_600_000L,
        newOnServer = 12,
        freeBytes = 48_000 * MB,
        loaded = true,
    )

    /** Similar photos just turned on: downloading. */
    val phoneSearchDownloading = PhoneSearchState(
        indexes.map { if (it.kind == PhoneIndexKind.SIMILAR) PhoneIndex(it.kind, true, bytes = it.bytes, keptBytes = 61 * MB) else it },
        download = PhoneDownload(PhoneIndexKind.SIMILAR, doneBytes = 61 * MB, totalBytes = 152 * MB),
        updatedAtMillis = System.currentTimeMillis() - 2 * 3_600_000L,
        freeBytes = 48_000 * MB,
    )

    /** Turned on away from Wi-Fi. */
    val phoneSearchWaiting = PhoneSearchState(
        indexes.map { PhoneIndex(it.kind, it.kind == PhoneIndexKind.WORDS, bytes = it.bytes, modelBytes = it.modelBytes) },
        waitingForWifi = true,
        freeBytes = 48_000 * MB,
    )

    /** Never turned on. */
    val phoneSearchOff = PhoneSearchState(indexes.map { PhoneIndex(it.kind, false, bytes = it.bytes, modelBytes = it.modelBytes) }, freeBytes = 48_000 * MB)
}
