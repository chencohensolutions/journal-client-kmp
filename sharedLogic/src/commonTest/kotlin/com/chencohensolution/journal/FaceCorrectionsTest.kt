package com.chencohensolution.journal

import com.chencohensolution.journal.local.FaceCorrection
import com.chencohensolution.journal.local.PeopleDoc
import com.chencohensolution.journal.local.PeopleDocItem
import com.chencohensolution.journal.local.applyCorrection
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FaceCorrectionsTest {
    private fun unit(vararg v: Float): FloatArray {
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    // Mom's own faces point along x; the aunt's are close to Mom's but closer to each other.
    private val vectors = mapOf(
        "m1:0" to unit(1f, 0f, 0f),
        "m2:0" to unit(1f, 0.1f, 0f),
        "a1:0" to unit(0.7f, 0.7f, 0f),
        "a2:0" to unit(0.68f, 0.72f, 0.05f),
        "a3:0" to unit(0.72f, 0.69f, 0f),
        "x1:0" to unit(0f, 0f, 1f),
    )

    private val doc = PeopleDoc(
        people = listOf(
            PeopleDocItem("p1", "Mom", faces = listOf("m1:0", "m2:0", "a1:0", "a2:0", "a3:0"), named = listOf("m1:0", "m2:0")),
            PeopleDocItem("p2", "Dana", faces = listOf("x1:0"), named = listOf("x1:0")),
            PeopleDocItem("g9", null, faces = listOf("z1:0", "z2:0", "z3:0")),
        ),
    )

    private fun apply(doc: PeopleDoc, c: FaceCorrection) = applyCorrection(doc, c) { vectors[it] }

    private fun PeopleDoc.of(id: String) = people.first { it.id == id }

    @Test
    fun notMomTakesHerLookalikesAway() {
        val after = apply(doc, FaceCorrection.Mark("p1", yes = emptyList(), no = listOf("a1:0")))
        val mom = after.of("p1")
        // a2 and a3 look more like a1 than like Mom's own faces; her own stay.
        assertEquals(listOf("m1:0", "m2:0"), mom.faces)
        assertEquals(listOf("a1:0"), mom.not)
    }

    @Test
    fun movingAndNamingAFace() {
        val moved = apply(doc, FaceCorrection.Move("a1:0", "p2"))
        assertTrue("a1:0" in moved.of("p2").faces && "a1:0" in moved.of("p2").named)
        assertTrue("a1:0" !in moved.of("p1").faces && "a1:0" in moved.of("p1").not)

        val named = apply(doc, FaceCorrection.NameFace("z1:0", "Avi", "local:1"))
        assertEquals(listOf("z1:0"), named.of("local:1").faces)
        assertEquals(listOf("z2:0", "z3:0"), named.of("g9").faces)
        // A name someone has already is them.
        val again = apply(doc, FaceCorrection.NameFace("z2:0", "dana", "local:2"))
        assertTrue("z2:0" in again.of("p2").faces && again.people.none { it.id == "local:2" })

        val confirmed = apply(doc, FaceCorrection.Mark("p1", yes = listOf("x1:0"), no = emptyList()))
        assertTrue("x1:0" in confirmed.of("p1").named && "x1:0" !in confirmed.of("p2").faces)
    }
}
