package com.chencohensolution.journal.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The user's corrections of faces on the phone (docs/screens/people.md "Correcting faces"): applied at once to the
// people document the phone keeps (On this phone › People), as the server would apply them, and queued while the
// server can't be reached. The server's people.json stays the record: what it sends next replaces the document.

/** A correction, as the server takes it. */
@Serializable
internal sealed interface FaceCorrection {
    /** The faces [yes] are [person]'s, and [no] are not (markFaces). */
    @Serializable
    @SerialName("mark")
    data class Mark(val person: String, val yes: List<String>, val no: List<String>) : FaceCorrection

    /** The face is [to]'s, or no one's (moveFace); whoever had it is never given it again. */
    @Serializable
    @SerialName("move")
    data class Move(val face: String, val to: String?) : FaceCorrection

    /** The face is the person called [name], or a new person of that name ([newId] here until the server answers). */
    @Serializable
    @SerialName("name")
    data class NameFace(val face: String, val name: String, val newId: String) : FaceCorrection
}

/** How alike faces must be, as the server's faces package: NotMatch, and the exemplars a person is known by. */
internal const val NOT_MATCH = 0.5f
private const val EXEMPLARS = 64

/**
 * [doc] with [correction] applied, as the server would put the faces together after it: faces move between people,
 * the choices (`named`, `not`) change, and a person's faces that look more like a face the user said they are not
 * than like their own faces leave them (the server's notFor). Faces are never added to a person by likeness here: the
 * server does that, and its next document says so. [vector] gives a face's vector (null: unknown).
 */
internal fun applyCorrection(doc: PeopleDoc, correction: FaceCorrection, vector: (String) -> FloatArray?): PeopleDoc {
    val people = doc.people.map { it.copy() }.toMutableList()
    fun owner(face: String) = people.indexOfFirst { face in it.faces }

    /** Takes [face] from whoever has it; a named person won't be given it again. */
    fun takeAway(face: String) {
        val i = owner(face)
        if (i < 0) return
        val p = people[i]
        people[i] = p.copy(faces = p.faces - face, named = p.named - face, not = if (p.name != null) p.not + face else p.not)
    }

    fun give(face: String, i: Int) {
        val p = people[i]
        people[i] = p.copy(faces = (p.faces - face) + face, named = (p.named - face) + face, not = p.not - face)
    }

    val touched = mutableSetOf<Int>()
    when (correction) {
        is FaceCorrection.Mark -> {
            val i = people.indexOfFirst { it.id == correction.person }
            if (i < 0) return doc
            for (face in correction.yes) {
                if (face !in people[i].faces) takeAway(face)
                give(face, i)
            }
            for (face in correction.no) {
                val p = people[i]
                people[i] = p.copy(faces = p.faces - face, named = p.named - face, not = (p.not - face) + face)
            }
            touched += i
        }
        is FaceCorrection.Move -> {
            val from = owner(correction.face)
            takeAway(correction.face)
            if (from >= 0) touched += from
            correction.to?.let { to -> people.indexOfFirst { it.id == to }.takeIf { it >= 0 }?.let { give(correction.face, it) } }
        }
        is FaceCorrection.NameFace -> {
            val from = owner(correction.face)
            var i = people.indexOfFirst { it.name.equals(correction.name, ignoreCase = true) }
            if (i < 0) {
                people += PeopleDocItem(correction.newId, correction.name)
                i = people.size - 1
            }
            if (from != i) {
                takeAway(correction.face)
                if (from >= 0) touched += from
            }
            give(correction.face, i)
        }
    }
    for (i in touched) {
        val p = people[i]
        if (p.name == null || p.not.isEmpty()) continue
        val nots = p.not.mapNotNull(vector)
        val examples = p.named.let { named -> named.filterIndexed { n, _ -> n % maxOf(1, named.size / EXEMPLARS) == 0 } }.mapNotNull(vector)
        val leaving = p.faces.filter { face -> face !in p.named && vector(face)?.let { notFor(it, nots, examples) } == true }
        if (leaving.isNotEmpty()) people[i] = p.copy(faces = p.faces - leaving.toSet())
    }
    return doc.copy(people = people.filter { it.faces.isNotEmpty() || it.name != null })
}

/**
 * Whether a face ([vector]) looks like one of [nots] (NotMatch or more) more than like any of [examples]: the server's
 * notFor, face to face.
 */
internal fun notFor(vector: FloatArray, nots: List<FloatArray>, examples: List<FloatArray>): Boolean {
    val not = nots.maxOfOrNull { dot(vector, it) } ?: return false
    if (not < NOT_MATCH) return false
    return examples.none { dot(vector, it) >= not }
}

@Serializable
internal data class CorrectionQueue(val v: Int = 1, val corrections: List<FaceCorrection> = emptyList())
