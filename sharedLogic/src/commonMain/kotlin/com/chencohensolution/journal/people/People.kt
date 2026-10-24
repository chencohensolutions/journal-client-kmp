package com.chencohensolution.journal.people

import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import com.chencohensolution.journal.graphql.ForgetPersonMutation
import com.chencohensolution.journal.graphql.MarkFacesMutation
import com.chencohensolution.journal.graphql.MergePeopleMutation
import com.chencohensolution.journal.graphql.NameFaceMutation
import com.chencohensolution.journal.graphql.MoveFaceMutation
import com.chencohensolution.journal.graphql.NamePersonMutation
import com.chencohensolution.journal.graphql.PeopleQuery
import com.chencohensolution.journal.graphql.PersonMediaQuery
import com.chencohensolution.journal.graphql.fragment.FaceFields
import com.chencohensolution.journal.graphql.fragment.PersonFields
import com.chencohensolution.journal.network.describe
import com.chencohensolution.journal.search.PeopleSearch
import com.chencohensolution.journal.search.SearchAnswer
import com.chencohensolution.journal.search.SearchUnreachable
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.server.firstServerError

// Faces and people, from the server's face search (journal-server-go docs/faces.md): the server finds the faces in
// each photo and puts alike faces in groups; the user names a group once ("Mom") and her faces are found from then on.

/** A part of a photo, as fractions of the upright photo's width and height (the server's Box; a preview's crop). */
data class FaceBox(val x: Float, val y: Float, val w: Float, val h: Float) {
    /**
     * The box [room] times as wide and tall around its middle, made square on the photo (whose [photoRatio] is
     * width / height) and kept inside it: what a round avatar shows of the face, with some hair and chin.
     */
    fun around(room: Float, photoRatio: Float): FaceBox {
        // In pixels of a photo 1 wide: the box's sides, the longer one as the square's side.
        val ratio = if (photoRatio > 0f) photoRatio else 1f
        val side = maxOf(w * ratio, h) * room
        val sw = (side / ratio).coerceAtMost(1f)
        val sh = side.coerceAtMost(1f)
        val cx = x + w / 2
        val cy = y + h / 2
        return FaceBox((cx - sw / 2).coerceIn(0f, 1f - sw), (cy - sh / 2).coerceIn(0f, 1f - sh), sw, sh)
    }
}

/**
 * What a round avatar of [face] asks the server for: the face with some hair and chin around it, square on the photo
 * of [photoWidth] × [photoHeight] (its upright size; 0 when unknown), as the preview's crop (x, y, w, h).
 */
fun faceCrop(face: Face, photoWidth: Int, photoHeight: Int): FloatArray {
    val ratio = if (photoWidth > 0 && photoHeight > 0) photoWidth.toFloat() / photoHeight else 1f
    val box = face.box.around(AVATAR_ROOM, ratio)
    return floatArrayOf(box.x, box.y, box.w, box.h)
}

/** How much wider than the face's box an avatar shows. */
private const val AVATAR_ROOM = 1.5f

/** A face in a photo: [id] is `<sha256>:<n>`; [personId] the person or group it is in, [personName] when named. */
data class Face(
    val id: String,
    val sha256: String,
    val box: FaceBox,
    val personId: String? = null,
    val personName: String? = null,
)

/**
 * Someone the user named ([name]), or a group of alike faces no one has named yet (null). A person's id starts with p
 * and never changes; a group's starts with g and may change whenever the server puts faces together again. [files]: how
 * many photos and videos show them; [cover] the clearest face, to show.
 */
data class Person(val id: String, val name: String?, val files: Int, val faces: Int, val cover: Face) {
    val isGroup: Boolean get() = name == null
}

/** The server's people: [people] named, by name; [groups] not named, most files first; [read] of [total] files read for faces. */
data class PeopleList(val people: List<Person>, val groups: List<Person>, val read: Int, val total: Int) {
    fun find(id: String): Person? = people.firstOrNull { it.id == id } ?: groups.firstOrNull { it.id == id }
}

/** A photo of a person's page: its sha256, the server's set and moment numbers, and the faces in it. */
internal data class PersonHit(val sha256: String, val set: Int, val moment: Int, val faces: List<Face>)

/** A page of a person's photos; [indexed] of [total] files read for faces (or for text, with words). */
internal data class PersonAnswer(val hits: List<PersonHit>, val indexed: Int, val total: Int)

/** The server couldn't be reached, or this device isn't registered. */
internal class PeopleUnreachable(message: String) : Exception(message)

/** The server's people changed since the app's list (a group renumbered): load the list again. */
internal class PeopleChanged(message: String) : Exception(message)

/** What the People screens ask of the server; each throws [PeopleUnreachable] offline, and other errors as they come. */
internal interface PeopleApi {
    suspend fun people(includePrivate: Boolean): PeopleList

    /** The photos with a face of every one of [ids], the last taken first; with [text], those it describes best. */
    suspend fun media(ids: List<String>, text: String?, limit: Int, offset: Int, includePrivate: Boolean): PersonAnswer

    /** Names the group [id] (it becomes a person), or renames the person; a group given a person's name joins them. */
    suspend fun name(id: String, name: String): Person

    /** The faces of [from] (people or groups) become [into]'s. */
    suspend fun merge(into: String, from: List<String>): Person

    /** Gives the face [faceId] to [to] (null: no one); the person it was in never gets it again. */
    suspend fun moveFace(faceId: String, to: String?)

    /** Forgets the person [id]: the name goes, and their faces go back into groups. */
    suspend fun forget(id: String)

    /**
     * Says, in one change, that the faces [yes] are [person]'s and [no] are not: a "No" also keeps faces more like it
     * than like them away from them (the server's markFaces).
     */
    suspend fun mark(person: String, yes: List<String>, no: List<String>): Person

    /** Gives the face [faceId] to the person called [name] (ignoring case), or to a new person of that name. */
    suspend fun nameFace(faceId: String, name: String): Person
}

/** [PeopleApi] over GraphQL, as this device's identity. */
internal class ApolloPeopleApi(private val http: ServerHttp) : PeopleApi {
    private suspend fun <T> ask(block: suspend (com.apollographql.apollo.ApolloClient) -> T): T =
        try {
            http.graphql(block) ?: throw PeopleUnreachable("This device isn't registered")
        } catch (e: ApolloNetworkException) {
            throw PeopleUnreachable(e.describe())
        } catch (e: ApolloHttpException) {
            throw PeopleUnreachable(e.describe())
        }

    override suspend fun people(includePrivate: Boolean): PeopleList = ask { apollo ->
        val response = apollo.query(PeopleQuery(Optional.presentIfNotNull(true.takeIf { includePrivate }))).execute()
        response.exception?.let { throw it }
        firstServerError(response.errors)?.let { throw it }
        val people = response.data?.people ?: throw IllegalStateException("No answer")
        val all = people.people.map { it.personFields.toPerson() }
        PeopleList(all.filter { !it.isGroup }, all.filter { it.isGroup }, people.read, people.total)
    }

    override suspend fun media(ids: List<String>, text: String?, limit: Int, offset: Int, includePrivate: Boolean): PersonAnswer = ask { apollo ->
        val response = apollo.query(
            PersonMediaQuery(
                people = Optional.present(ids),
                text = Optional.presentIfNotNull(text?.takeIf { it.isNotBlank() }),
                limit = Optional.present(limit),
                offset = Optional.presentIfNotNull(offset.takeIf { it > 0 }),
                includePrivate = Optional.presentIfNotNull(true.takeIf { includePrivate }),
            ),
        ).execute()
        response.exception?.let { throw it }
        firstServerError(response.errors)?.let { error ->
            throw if ("load the people again" in error.message.orEmpty()) PeopleChanged(error.message.orEmpty()) else error
        }
        val found = response.data?.searchMedia ?: throw IllegalStateException("No answer")
        PersonAnswer(
            found.hits.map { hit -> PersonHit(hit.media.sha256, hit.set, hit.moment, hit.media.faces.map { it.faceFields.toFace() }) },
            found.indexed,
            found.total,
        )
    }

    override suspend fun name(id: String, name: String): Person = ask { apollo ->
        val response = apollo.mutation(NamePersonMutation(id, name)).execute()
        response.exception?.let { throw it }
        firstServerError(response.errors)?.let { throw it }
        val result = response.data?.namePerson ?: throw IllegalStateException("No answer")
        result.onPerson?.personFields?.toPerson() ?: throw PeopleChanged(result.onNotFound?.reason ?: "Not found")
    }

    override suspend fun merge(into: String, from: List<String>): Person = ask { apollo ->
        val response = apollo.mutation(MergePeopleMutation(into, from)).execute()
        response.exception?.let { throw it }
        firstServerError(response.errors)?.let { throw it }
        val result = response.data?.mergePeople ?: throw IllegalStateException("No answer")
        result.onPerson?.personFields?.toPerson() ?: throw PeopleChanged(result.onNotFound?.reason ?: "Not found")
    }

    override suspend fun moveFace(faceId: String, to: String?) = ask { apollo ->
        val response = apollo.mutation(MoveFaceMutation(faceId, Optional.present(to))).execute()
        response.exception?.let { throw it }
        firstServerError(response.errors)?.let { throw it }
        val result = response.data?.moveFace ?: throw IllegalStateException("No answer")
        result.onNotFound?.let { throw PeopleChanged(it.reason) }
        Unit
    }

    override suspend fun mark(person: String, yes: List<String>, no: List<String>): Person = ask { apollo ->
        val response = apollo.mutation(MarkFacesMutation(person, yes, no)).execute()
        response.exception?.let { throw it }
        firstServerError(response.errors)?.let { throw it }
        val result = response.data?.markFaces ?: throw IllegalStateException("No answer")
        result.onPerson?.personFields?.toPerson() ?: throw PeopleChanged(result.onNotFound?.reason ?: "Not found")
    }

    override suspend fun nameFace(faceId: String, name: String): Person = ask { apollo ->
        val response = apollo.mutation(NameFaceMutation(faceId, name)).execute()
        response.exception?.let { throw it }
        firstServerError(response.errors)?.let { throw it }
        val result = response.data?.nameFace ?: throw IllegalStateException("No answer")
        result.onPerson?.personFields?.toPerson() ?: throw PeopleChanged(result.onNotFound?.reason ?: "Not found")
    }

    override suspend fun forget(id: String) = ask { apollo ->
        val response = apollo.mutation(ForgetPersonMutation(id)).execute()
        response.exception?.let { throw it }
        firstServerError(response.errors)?.let { throw it }
        if (response.data?.forgetPerson != true) throw PeopleChanged("No such person")
    }
}

internal fun FaceFields.toFace() = Face(
    id = id,
    sha256 = sha256,
    box = FaceBox(box.x.toFloat(), box.y.toFloat(), box.w.toFloat(), box.h.toFloat()),
    personId = personId,
    personName = personName,
)

private fun PersonFields.toPerson() = Person(id = id, name = name, files = files, faces = faces, cover = cover.faceFields.toFace())

/** Search with people chosen, over [api]: their photos as a search answer, numbered by set and moment as the server does. */
internal class ApiPeopleSearch(private val api: PeopleApi, private val includePrivate: () -> Boolean) : PeopleSearch {
    override suspend fun search(ids: List<String>, text: String?, limit: Int, offset: Int): SearchAnswer {
        val answer = try {
            api.media(ids, text, limit, offset, includePrivate())
        } catch (e: PeopleUnreachable) {
            throw SearchUnreachable(e.message ?: "")
        }
        return SearchAnswer(
            answer.hits.map { it.sha256 },
            answer.indexed,
            answer.total,
            answer.hits.map { it.set }.takeIf { sets -> sets.none { it < 0 } }.orEmpty(),
            answer.hits.map { it.moment }.takeIf { moments -> moments.none { it < 0 } }.orEmpty(),
        )
    }
}
