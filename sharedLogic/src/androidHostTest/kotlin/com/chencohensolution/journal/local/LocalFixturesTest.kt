package com.chencohensolution.journal.local

import com.chencohensolution.journal.server.parseTimestamp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

// The phone's text-in-photos matching and search grouping against the server's answers (local-fixtures, written by
// journal-server-go's scripts/local-fixtures).
class LocalFixturesTest {
    private fun fixture(name: String) = Json.parseToJsonElement(File("src/commonTest/resources/local-fixtures/$name").readText()).jsonObject

    @Test
    fun splitsWordsAsTheServerDoes() {
        for (case in fixture("ocr.json")["tokenize"]!!.jsonArray) {
            val text = case.jsonObject["text"]!!.jsonPrimitive.content
            val words = case.jsonObject["words"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(words, ocrWords(text), "words of \"$text\"")
        }
    }

    @Test
    fun matchesWordsAsTheServerDoes() {
        for (case in fixture("ocr.json")["matches"]!!.jsonArray) {
            val o = case.jsonObject
            val word = o["word"]!!.jsonPrimitive.content
            val query = o["query"]!!.jsonPrimitive.content
            assertEquals(o["matches"]!!.jsonPrimitive.boolean, ocrMatches(word, query), "\"$word\" for \"$query\"")
        }
    }

    @Test
    fun findsTextAsTheServerDoes() {
        val ocr = fixture("ocr.json")
        val lines = ocr["texts"]!!.jsonObject.mapValues { (_, list) -> list.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content } }
        val words = wordIndex(lines)
        for (case in ocr["find"]!!.jsonArray) {
            val query = case.jsonObject["query"]!!.jsonPrimitive.content
            val found = case.jsonObject["found"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(found, ocrFind(words, query) { true }, "found for \"$query\"")
        }
    }

    @Test
    fun groupsHitsAsTheServerDoes() {
        val groups = fixture("groups.json")
        val hits = groups["hits"]!!.jsonArray.map { it.jsonObject }
        val vectors = hits.associate { h ->
            h["sha256"]!!.jsonPrimitive.content to (h["vector"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.float }?.toFloatArray())
        }
        val times = hits.associate { h ->
            h["sha256"]!!.jsonPrimitive.content to (h["takenAt"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let(::parseTimestamp)?.millis)
        }
        val (sets, moments) = searchGroups(hits.map { it["sha256"]!!.jsonPrimitive.content }, { vectors[it] }, { times[it] })
        assertEquals(groups["sets"]!!.jsonArray.map { it.jsonPrimitive.int }, sets)
        assertEquals(groups["moments"]!!.jsonArray.map { it.jsonPrimitive.int }, moments)
    }

    private fun JsonObject.has(key: String) = containsKey(key)
}
