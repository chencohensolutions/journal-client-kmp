package com.chencohensolution.journal

import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.server.HiddenCollections
import com.chencohensolution.journal.server.ServerLibrary
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class HiddenStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class HiddenCollectionsTest {
    private val bumble = "b".repeat(64)
    private val photo = "c".repeat(64)

    private fun media(sha: String) = """{"sha256":"$sha","kind":"PHOTO","fileName":"x.jpg","mimeType":"image/jpeg","size":10,""" +
        """"takenAt":null,"width":null,"height":null,"durationMs":null,"location":null,"source":"screenshots","uploadedAt":"2026-10-22T10:00:00Z",""" +
        """"nudity":null,"private":false}"""

    private val collections = """{"__typename":"HiddenCollections","whatsapp":{"__typename":"Collection","name":"WhatsApp","files":0,"hidden":false},""" +
        """"screenshotsWithoutApp":{"__typename":"Collection","name":"","files":0,"hidden":false},""" +
        """"screenshotApps":[{"__typename":"Collection","name":"Bumble","files":1,"hidden":false}]}"""

    @Test
    fun aFileTheServerHidesIsDroppedWithWhatThisPhoneKeepsOfIt() = runTest {
        var hidden = false
        val sent = mutableListOf<String>()
        val factory = MtlsHttpClientFactory { _, _ ->
            HttpClient(MockEngine { request ->
                val body = (request.body as io.ktor.http.content.TextContent).text
                sent += body
                val json = when {
                    "SetHiddenCollections" in body -> {
                        hidden = true
                        """{"data":{"setHiddenCollections":${collections.replace(""""files":1,"hidden":false""", """"files":1,"hidden":true""")}}}"""
                    }
                    "HiddenCollections" in body -> """{"data":{"hiddenCollections":$collections}}"""
                    hidden -> """{"data":{"changes":{"cursor":"c2","hasMore":false,"changes":[{"kind":"MEDIA","path":"media/$bumble","deleted":true,"media":null}]}}}"""
                    else -> """{"data":{"changes":{"cursor":"c1","hasMore":false,"changes":[""" +
                        """{"kind":"MEDIA","path":"media/$bumble","deleted":false,"media":${media(bumble)}},""" +
                        """{"kind":"MEDIA","path":"media/$photo","deleted":false,"media":${media(photo)}}]}}}"""
                }
                respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            })
        }
        val settings = JournalSettings(HiddenStore()).apply { identityAlias = "hen" }
        val removed = mutableListOf<String>()
        val library = ServerLibrary(
            settings, factory, emptyList(), FakeFileSystem(), "/server".toPath(), StandardTestDispatcher(testScheduler),
            removed = { removed += it },
        )
        library.sync()
        assertEquals(setOf(bumble, photo), library.media.value.map { it.sha256 }.toSet())

        val shown = library.hiddenCollections()!!
        assertEquals(listOf(HiddenCollections.Collection("Bumble", 1, false)), shown.screenshotApps)
        val saved = library.setHiddenCollections(shown.with(shown.screenshotApps[0], hidden = true))!!
        assertEquals(true, saved.screenshotApps[0].hidden)
        assert(sent.any { """"screenshotApps":["Bumble"]""" in it && """"whatsapp":false""" in it }) { sent.joinToString("\n") }

        // The sync after the change drops it, and tells the phone to delete what it keeps of it.
        assertEquals(listOf(photo), library.media.value.map { it.sha256 })
        assertEquals(listOf(bumble), removed)
    }

    @Test
    fun withChangesOnlyTheCollectionGiven() {
        val whatsapp = HiddenCollections.Collection("WhatsApp", 3, false)
        val app = HiddenCollections.Collection("WhatsApp", 3, false)
        val choice = HiddenCollections(whatsapp, HiddenCollections.Collection("", 0, false), listOf(app))
        val changed = choice.with(choice.screenshotApps[0], hidden = true)
        assertEquals(false, changed.whatsapp.hidden)
        assertEquals(true, changed.screenshotApps[0].hidden)
    }

    @Test
    fun unreachableIsNull() = runTest {
        val factory = MtlsHttpClientFactory { _, _ -> HttpClient(MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }) }
        val settings = JournalSettings(HiddenStore()).apply { identityAlias = "hen" }
        val library = ServerLibrary(settings, factory, emptyList(), FakeFileSystem(), "/server".toPath(), StandardTestDispatcher(testScheduler))
        assertNull(library.hiddenCollections())
    }
}
