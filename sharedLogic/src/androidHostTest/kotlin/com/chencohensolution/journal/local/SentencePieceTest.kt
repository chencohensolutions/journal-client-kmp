package com.chencohensolution.journal.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

// The phone's tokenizer against the server's answers (local-fixtures/tokens.json, from journal-server-go's
// scripts/local-fixtures). Needs the server's search model beside this repo for tokenizer.model; skipped without it.
class SentencePieceTest {
    private val fixtures = File("src/commonTest/resources/local-fixtures/tokens.json")
    private val tokenizer = File("../../journal-server-go/models/siglip2-base-patch16-256/tokenizer.model")

    @Test
    fun readsQueriesAsTheServerDoes() {
        if (!fixtures.exists() || !tokenizer.exists()) return
        val model = SentencePiece(tokenizer.readBytes())
        val cases = Json.parseToJsonElement(fixtures.readText()).jsonObject["cases"]!!.jsonArray
        for (case in cases) {
            val text = case.jsonObject["text"]!!.jsonPrimitive.content
            val expected = case.jsonObject["tokens"]!!.jsonArray.map { it.jsonPrimitive.long }
            assertEquals(expected, model.textTokens(text).toList(), "tokens of \"$text\"")
        }
    }
}
