package com.chencohensolution.journal.local

import com.chencohensolution.journal.people.Face
import com.chencohensolution.journal.people.FaceBox
import com.chencohensolution.journal.people.FindingsApi
import com.chencohensolution.journal.people.PeopleApi
import com.chencohensolution.journal.people.PeopleList
import com.chencohensolution.journal.people.PeopleUnreachable
import com.chencohensolution.journal.people.Person
import com.chencohensolution.journal.people.PersonAnswer
import com.chencohensolution.journal.people.PersonHit
import com.chencohensolution.journal.search.SearchAnswer
import com.chencohensolution.journal.search.SearchApi
import com.chencohensolution.journal.search.SearchUnreachable
import com.chencohensolution.journal.server.FileFacts
import com.chencohensolution.journal.server.parseTimestamp
import com.chencohensolution.journal.stories.GeoArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import okio.FileSystem
import okio.Path.Companion.toPath

// Searching on the phone with the indexes it keeps (LocalIndexes), as the server searches (journal-server-go
// graph/resolver/search.resolvers.go): the photos whose own text has the words first, then those SigLIP finds best,
// grouped into sets and moments the same way. And the fallbacks for People and a photo's details while the server
// can't be reached.

/** Files the server read that the phone may still lack while its search counts as up to date. */
private const val BEHIND = 20

/** Walks the server's files this device sees, a few facts each (ServerLibrary.forEachFile). */
internal typealias FileWalk = suspend (each: (FileFacts) -> Unit) -> Unit

/**
 * The server's files the phone's search or people may show: when each was taken ([taken], epoch milliseconds, null when
 * its time can't be read), by sha256, and how many files there are in all ([total]), before [shownFiles]' years and area.
 */
internal class ShownFiles(val taken: Map<String, Long?>, val total: Int)

/**
 * The files of [walk] that are shown (never those tucked away): private ones only with [includePrivate], of [years] and inside [area] when given.
 * Read a row at a time, so only these few facts of the files kept are in memory, and only while a search runs (step
 * 6e; before, the whole list was held for it).
 */
internal suspend fun shownFiles(walk: FileWalk, includePrivate: Boolean, years: List<Int>? = null, area: GeoArea? = null): ShownFiles {
    val taken = HashMap<String, Long?>()
    var total = 0
    walk each@{ file ->
        // What is tucked away is never found, as the server's search leaves it out.
        if ((file.isPrivate && !includePrivate) || file.isTucked) return@each
        total++
        val time = parseTimestamp(file.takenAt ?: file.uploadedAt)
        if (years != null && time?.day?.year !in years) return@each
        if (area != null) {
            val lat = file.latitude ?: return@each
            val lon = file.longitude ?: return@each
            if (lat < area.south || lat > area.north || lon < area.west || lon > area.east) return@each
        }
        taken[file.sha256] = time?.millis
    }
    return ShownFiles(taken, total)
}

/** What the phone's search may find: the server's files this device sees ([files]); private ones only when shown. */
internal class LocalSearchApi(
    private val indexes: LocalIndexes,
    private val files: FileWalk,
    private val includePrivate: () -> Boolean,
    private val fileSystem: FileSystem,
) : SearchApi {
    private var words: Pair<Int, TextWords>? = null

    /** Whether this phone keeps something to search words with. */
    val canSearch: Boolean get() = indexes.canSearchWords || indexes.table(PhoneIndexKind.TEXT) != null

    /** Whether what it keeps is up to date: at most a few files the server read since aren't here. */
    val upToDate: Boolean get() = indexes.currentState.newOnServer <= BEHIND

    override suspend fun search(text: String, limit: Int, offset: Int, years: List<Int>?, area: GeoArea?): SearchAnswer = withContext(Dispatchers.Default) {
        val shown = shownFiles(files, includePrivate(), years, area)
        val keep: (String) -> Boolean = { sha -> sha in shown.taken }
        val wanted = offset + limit
        val hits = ArrayList<String>()
        val inText = HashSet<String>()
        val texts = indexes.table(PhoneIndexKind.TEXT) as? TextTable
        if (texts != null) {
            for (sha in ocrFind(wordsOf(texts), text, keep)) {
                if (hits.size == wanted) break
                hits += sha
                inText += sha
            }
        }
        val vectors = indexes.table(PhoneIndexKind.WORDS) as? VectorTable
        val model = indexes.textModel()
        val encoding = indexes.encoding
        if (vectors != null && model != null && encoding != null && hits.size < wanted) {
            val tokens = tokenizer(model)?.textTokens(text) ?: throw SearchUnreachable("The search model's tokenizer is missing")
            val query = encoding.encode(model, tokens) ?: throw SearchUnreachable("The search model couldn't run")
            unit(query)
            vectors.top(query, wanted - hits.size) { it !in inText && keep(it) }.forEach { hits += it.first }
        } else if (texts == null) {
            throw SearchUnreachable("Nothing to search with on this phone")
        }
        val (sets, moments) = searchGroups(
            hits,
            vector = { sha -> vectors?.vector(sha) },
            takenAt = { sha -> shown.taken[sha] },
        )
        val indexed = (vectors ?: texts)?.size ?: 0
        val total = shown.total
        SearchAnswer(
            hits.drop(offset),
            indexed = minOf(indexed, total),
            total = total,
            sets = sets.drop(offset),
            moments = moments.drop(offset),
            onPhone = true,
        )
    }

    /** The text table's words, made again when the table changed. */
    private fun wordsOf(table: TextTable): TextWords {
        val version = indexes.version.value
        words?.takeIf { it.first == version }?.let { return it.second }
        return wordIndex(table).also { words = version to it }
    }

    private var tokenizer: Pair<String, SentencePiece>? = null

    /** The tokenizer beside [model] (`tokenizer.model`), read once. */
    private fun tokenizer(model: String): SentencePiece? {
        val path = model.substringBeforeLast('/') + "/tokenizer.model"
        tokenizer?.takeIf { it.first == path }?.let { return it.second }
        val bytes = runCatching { fileSystem.read(path.toPath()) { readByteArray() } }.getOrNull() ?: return null
        return SentencePiece(bytes).also { tokenizer = path to it }
    }

    private fun unit(v: FloatArray) {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        if (sum == 0.0) return
        val scale = (1 / kotlin.math.sqrt(sum)).toFloat()
        for (i in v.indices) v[i] *= scale
    }
}

/**
 * Search on the phone when it keeps what search needs and [preferPhone] says so (Settings › On this phone), else on the
 * server; when the server can't be reached, on the phone anyway if it can.
 */
internal class PhoneFirstSearchApi(
    private val local: LocalSearchApi,
    private val remote: SearchApi,
) : SearchApi {
    override suspend fun search(text: String, limit: Int, offset: Int, years: List<Int>?, area: GeoArea?): SearchAnswer {
        if (local.canSearch && local.upToDate) {
            try {
                return local.search(text, limit, offset, years, area).copy(onPhone = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The phone's copy couldn't answer (a download changing it, a model that won't run): ask the server.
            }
        }
        return try {
            remote.search(text, limit, offset, years, area)
        } catch (e: SearchUnreachable) {
            if (!local.canSearch) throw e
            local.search(text, limit, offset, years, area)
        }
    }
}

/**
 * People from the server; while it can't be reached, from the people this phone keeps (On this phone › People).
 * Corrections of faces are applied to what the phone keeps at once, and while the server can't be reached they wait
 * in a queue, sent (in order) before the next list is asked for.
 */
internal class PhoneFallbackPeopleApi(
    private val remote: PeopleApi,
    private val indexes: LocalIndexes,
    /** The server's files this device sees (ServerLibrary.forEachFile). */
    private val files: FileWalk,
    private val newId: () -> String = { "local:" + kotlin.random.Random.nextLong().toULong().toString(16) },
) : PeopleApi by remote {
    override suspend fun people(includePrivate: Boolean): PeopleList = try {
        flush()
        remote.people(includePrivate)
    } catch (e: PeopleUnreachable) {
        localPeople(includePrivate) ?: throw e
    }

    /** Sends the corrections waiting, oldest first; stops at the first the server can't take now. */
    private suspend fun flush() {
        for (correction in indexes.pendingCorrections()) {
            try {
                send(correction)
            } catch (e: PeopleUnreachable) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Refused (the face or person is gone): dropped, as the server's people are the record.
            }
            indexes.sent(correction)
        }
    }

    private suspend fun send(correction: FaceCorrection): Person? = when (correction) {
        is FaceCorrection.Mark -> remote.mark(correction.person, correction.yes, correction.no)
        is FaceCorrection.Move -> {
            remote.moveFace(correction.face, correction.to)
            null
        }
        is FaceCorrection.NameFace -> remote.nameFace(correction.face, correction.name)
    }

    /**
     * [correction] sent to the server, and applied to what the phone keeps; offline, queued and applied here only.
     * The person it gives back is the server's, or (offline) made from the phone's people.
     */
    private suspend fun correct(correction: FaceCorrection, personId: String?): Person? {
        val answer = try {
            send(correction)
        } catch (e: PeopleUnreachable) {
            if (!indexes.correct(correction, queue = true)) throw e
            return personId?.let { id -> localPeople(includePrivate = true)?.find(id) }
        }
        indexes.correct(correction, queue = false)
        return answer
    }

    override suspend fun mark(person: String, yes: List<String>, no: List<String>): Person =
        correct(FaceCorrection.Mark(person, yes, no), person) ?: throw PeopleUnreachable("offline")

    override suspend fun moveFace(faceId: String, to: String?) {
        correct(FaceCorrection.Move(faceId, to), to)
    }

    override suspend fun nameFace(faceId: String, name: String): Person {
        val correction = FaceCorrection.NameFace(faceId, name, newId())
        return correct(correction, correction.newId)
            ?: localPeople(includePrivate = true)?.let { list -> (list.people + list.groups).firstOrNull { it.name.equals(name, ignoreCase = true) } }
            ?: throw PeopleUnreachable("offline")
    }

    override suspend fun media(ids: List<String>, text: String?, limit: Int, offset: Int, includePrivate: Boolean): PersonAnswer = try {
        remote.media(ids, text, limit, offset, includePrivate)
    } catch (e: PeopleUnreachable) {
        localMedia(ids, includePrivate)?.let { PersonAnswer(it.drop(offset).take(limit), it.size, it.size) } ?: throw e
    }

    /** The files shown, by sha256, with when each was taken. */
    private suspend fun visible(includePrivate: Boolean): Map<String, Long?> = shownFiles(files, includePrivate).taken

    private fun faceOf(id: String, personId: String?, name: String?): Face? {
        val sha = id.substringBefore(':')
        val n = id.substringAfter(':').toIntOrNull() ?: return null
        val box = (indexes.table(PhoneIndexKind.PEOPLE) as? FaceTable)?.box(sha, n) ?: return null
        return Face(id, sha, FaceBox(box[0], box[1], box[2], box[3]), personId, name)
    }

    private suspend fun localPeople(includePrivate: Boolean): PeopleList? {
        val doc = indexes.peopleDoc() ?: return null
        val files = visible(includePrivate)
        val all = doc.people.mapNotNull { p ->
            val faces = p.faces.filter { it.substringBefore(':') in files }
            if (faces.isEmpty()) return@mapNotNull null
            val cover = faces.firstNotNullOfOrNull { faceOf(it, p.id, p.name) } ?: return@mapNotNull null
            Person(p.id, p.name?.takeIf { it.isNotEmpty() }, faces.map { it.substringBefore(':') }.toSet().size, faces.size, cover)
        }
        return PeopleList(all.filter { !it.isGroup }, all.filter { it.isGroup }, files.size, files.size)
    }

    private suspend fun localMedia(ids: List<String>, includePrivate: Boolean): List<PersonHit>? {
        val doc = indexes.peopleDoc() ?: return null
        val files = visible(includePrivate)
        val members = doc.people.filter { it.id in ids }
        if (members.size != ids.size) return null
        val shas = members.map { m -> m.faces.map { it.substringBefore(':') }.toSet() }.reduce { a, b -> a intersect b }.filter { it in files }
        val facesBySha = doc.people.flatMap { p -> p.faces.map { it to p } }.groupBy { it.first.substringBefore(':') }
        return shas.sortedByDescending { sha -> files[sha] ?: 0L }
            .map { sha ->
                val faces = facesBySha[sha].orEmpty().mapNotNull { (id, p) -> faceOf(id, p.id, p.name?.takeIf { it.isNotEmpty() }) }
                PersonHit(sha, -1, -1, faces)
            }
    }
}

/** A photo's faces, text and similar photos from the server; while it can't be reached, from what this phone keeps. */
internal class PhoneFallbackFindingsApi(
    private val remote: FindingsApi,
    private val indexes: LocalIndexes,
) : FindingsApi {
    override suspend fun media(sha256: String): Pair<List<Face>, List<String>>? {
        val answer = try {
            remote.media(sha256)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        // The text's lines are read from their file.
        return answer ?: withContext(Dispatchers.IO) { local(sha256) }
    }

    override suspend fun similar(sha256: String, limit: Int, includePrivate: Boolean): List<String> {
        val answer = try {
            remote.similar(sha256, limit, includePrivate)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (!answer.isNullOrEmpty()) return answer
        val table = indexes.table(PhoneIndexKind.SIMILAR) as? VectorTable ?: return answer.orEmpty()
        val query = table.vector(sha256) ?: return answer.orEmpty()
        return withContext(Dispatchers.Default) {
            table.top(query, limit) { it != sha256 }.map { it.first }
        }
    }

    private fun local(sha256: String): Pair<List<Face>, List<String>>? {
        val faces = indexes.table(PhoneIndexKind.PEOPLE) as? FaceTable
        val texts = indexes.table(PhoneIndexKind.TEXT) as? TextTable
        if (faces == null && texts == null) return null
        val doc = indexes.peopleDoc()
        val owner = doc?.people.orEmpty().flatMap { p -> p.faces.map { it to p } }.toMap()
        val boxes = faces?.boxes(sha256)
        val found = if (boxes == null) emptyList() else (0 until boxes.size / 4).map { n ->
            val id = "$sha256:$n"
            val p = owner[id]
            Face(id, sha256, FaceBox(boxes[4 * n], boxes[4 * n + 1], boxes[4 * n + 2], boxes[4 * n + 3]), p?.id, p?.name?.takeIf { it.isNotEmpty() })
        }
        return found to texts?.lines(sha256).orEmpty()
    }
}
