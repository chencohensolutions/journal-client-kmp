package com.chencohensolution.journal

import com.chencohensolution.journal.swap.CopyKeyKind
import com.chencohensolution.journal.swap.KeptSwap
import com.chencohensolution.journal.swap.MySwap
import com.chencohensolution.journal.swap.Passphrase
import com.chencohensolution.journal.swap.ShownInvite
import com.chencohensolution.journal.swap.SwapApi
import com.chencohensolution.journal.swap.SwapController
import com.chencohensolution.journal.swap.SwapInfo
import com.chencohensolution.journal.swap.SwapOffer
import com.chencohensolution.journal.swap.SwapOutcome
import com.chencohensolution.journal.swap.SwapQuota
import com.chencohensolution.journal.swap.SwapRefusal
import com.chencohensolution.journal.swap.SwapsAnswer
import com.chencohensolution.journal.swap.parseSwapLink
import com.chencohensolution.journal.swap.refusal
import com.chencohensolution.journal.graphql.type.SwapRefusal as ServerSwapRefusal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GB = 1_000_000_000L
private const val ID = "MFZWI3D-BONSGYC-MFZWI3D-BONSGYC-MFZWI3D-BONSGYC-MFZWI3D-BONSGYC"

/** A friend's server as the test sets it, counting what it was asked. */
private class FakeSwapApi : SwapApi {
    var info: SwapInfo? = SwapInfo(available = true, freeSpace = 2_000 * GB, copyKey = CopyKeyKind.NONE, serverId = "AAAA")
    var answer = SwapsAnswer(emptyList(), emptyList(), emptyList())
    var swapsCalls = 0
    var infoCalls = 0
    var fail = false
    val joins = mutableListOf<List<Any?>>()
    val accepted = mutableListOf<Triple<String, String?, Boolean>>()
    val declined = mutableListOf<String>()
    val removed = mutableListOf<String>()
    val revoked = mutableListOf<Pair<String, Boolean>>()
    var joinOutcome: SwapOutcome = SwapOutcome.Joined(MySwap("dana", 500 * GB, 1, true, null), offered = true)

    override suspend fun info(): SwapInfo? {
        infoCalls++
        if (fail) throw IllegalStateException("can't reach the server")
        return info
    }

    override suspend fun swaps(): SwapsAnswer {
        swapsCalls++
        if (fail) throw IllegalStateException("can't reach the server")
        return answer
    }

    override suspend fun invite(name: String, quota: Long) = ShownInvite(name, quota, "journal://swap?code=AB12", "AB12", 600_000)

    override suspend fun join(link: String, name: String, passphrase: String?, plain: Boolean, offerQuota: Long?): SwapOutcome {
        joins += listOf(link, name, passphrase, plain, offerQuota)
        return joinOutcome
    }

    override suspend fun accept(id: String, passphrase: String?, plain: Boolean): SwapOutcome {
        accepted += Triple(id, passphrase, plain)
        return joinOutcome
    }

    override suspend fun decline(id: String): Boolean {
        declined += id
        return true
    }

    override suspend fun remove(name: String): Boolean {
        removed += name
        return true
    }

    override suspend fun revoke(name: String, deleteFiles: Boolean): Boolean {
        revoked += name to deleteFiles
        return true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SwapControllerTest {
    @Test
    fun aSwapLinkIsReadFromTheLinkOrAMessage() {
        val link = "journal://swap?address=https%3A%2F%2F203.0.113.5%3A56398&code=K7QM-4XPD&from=dana&id=$ID&key=" + "ab".repeat(32)
        val parsed = assertNotNull(parseSwapLink(link))
        assertEquals("K7QM-4XPD", parsed.code)
        assertEquals(ID, parsed.serverId)
        assertEquals(listOf("https://203.0.113.5:56398"), parsed.addresses)
        assertEquals("dana", parsed.from)
        assertEquals(link, parsed.link)
        assertEquals(link, parseSwapLink("Dana: keep my copy? $link\nthanks")?.link)

        assertNull(parseSwapLink("https://example.com"))
        assertNull(parseSwapLink("journal://enroll?code=K7QM-4XPD&id=$ID&key=" + "ab".repeat(32)))
        assertNull(parseSwapLink("journal://swap?id=$ID&key=" + "ab".repeat(32)), "no code")
        assertNull(parseSwapLink("journal://swap?code=K7QM&id=NOTANID&key=" + "ab".repeat(32)), "bad id")
        assertNull(parseSwapLink("journal://swap?code=K7QM&id=$ID"), "nowhere to find it")
        assertNull(parseSwapLink("journal://swap?code=K7QM&id=$ID&key=xyz"), "bad key")
        assertNotNull(parseSwapLink("journal://swap?code=K7QM&id=$ID&address=https%3A%2F%2Fh%3A1"), "addresses without a key")
    }

    @Test
    fun everyRefusalOfTheServerIsMapped() {
        val known = ServerSwapRefusal.knownEntries
        assertEquals(11, known.size)
        for (reason in known) assertEquals(reason.name, refusal(reason).name)
        assertEquals(SwapRefusal.OTHER, refusal(ServerSwapRefusal.safeValueOf("NEW_ONE")))
    }

    @Test
    fun theSuggestedPassphraseIsSixListWordsWithTwoToTypeBack() {
        val p = Passphrase.suggest()
        assertEquals(6, p.words.size)
        assertEquals(2, p.check.size)
        assertTrue(p.check[0] < p.check[1] && p.check.all { it in 0..5 })
        assertNull(Passphrase.weakness(p.text), "six words are always strong enough: ${p.text}")
        assertTrue(p.typedBack(listOf(" " + p.words[p.check[0]].uppercase(), p.words[p.check[1]] + " ")))
        assertFalse(p.typedBack(listOf("notaword1", p.words[p.check[1]])))
        assertFalse(p.typedBack(listOf(p.words[p.check[0]])))
        assertEquals(7772, Passphrase.wordListSize)
        // Different each time (77 bits: the same twice would be a broken source).
        assertTrue(Passphrase.suggest().text != Passphrase.suggest().text)
    }

    @Test
    fun weaknessCountsCharactersAndWords() {
        assertNull(Passphrase.weakness("abcdefghijklmnopqrstuvwx"), "24 characters in one word")
        assertNotNull(Passphrase.weakness("abcdefghijklmnopqrstuvw"), "23 in one word")
        assertNull(Passphrase.weakness("aaaa bbbb cccc dddd e"), "21 characters in 5 words")
        assertNotNull(Passphrase.weakness("aaaaaa bbbbbb cccccc"), "20 characters in 3 words")
        assertNull(Passphrase.weakness("aaaa-bbbb-cccc-ddddee"), "hyphens split words, as on the server")
        assertEquals(9, Passphrase.weakness("  short one ")?.characters)
    }

    @Test
    fun quotaChoicesStopAtTheFreeSpace() {
        assertEquals(listOf(50 * GB, 100 * GB, 250 * GB, 300 * GB), SwapQuota.choices(300 * GB))
        assertEquals(100 * GB, SwapQuota.suggested(300 * GB))
        assertEquals(500 * GB, SwapQuota.suggested(2_000 * GB))
        assertEquals(listOf(10 * GB), SwapQuota.choices(10 * GB))
        assertEquals(10 * GB, SwapQuota.suggested(10 * GB))
        assertEquals(emptyList(), SwapQuota.choices(0))
    }

    @Test
    fun refreshAsksForTheInfoAndTheSwaps() = runTest {
        val api = FakeSwapApi()
        api.answer = SwapsAnswer(
            listOf(MySwap("dana", 500 * GB, 1, true, null)),
            listOf(KeptSwap("dana", 200 * GB, 50 * GB, 1, null)),
            emptyList(),
        )
        val swaps = SwapController(api, StandardTestDispatcher(testScheduler))
        swaps.refresh()
        runCurrent()
        val s = swaps.currentState
        assertTrue(s.loaded && s.canKeepFriends)
        assertEquals(2_000 * GB, s.info?.freeSpace)
        assertEquals(0.25f, s.kept.single().fraction)
        assertTrue(s.nameTaken(" Dana "))
        assertFalse(s.nameTaken("noa"))
    }

    @Test
    fun aServerWithoutSwapsSaysSo() = runTest {
        val api = FakeSwapApi().apply { info = null }
        val swaps = SwapController(api, StandardTestDispatcher(testScheduler))
        swaps.refresh()
        runCurrent()
        assertTrue(swaps.currentState.unsupported)
        assertEquals(0, api.swapsCalls)
    }

    @Test
    fun whileAnInviteShowsTheSwapsAreAskedQuicklyAndTheFriendsJoinAndOfferAppear() = runTest {
        var now = 0L
        val api = FakeSwapApi()
        val swaps = SwapController(api, StandardTestDispatcher(testScheduler), inviteEveryMillis = 3_000, idleEveryMillis = 30_000, now = { now })
        val watch = swaps.watch()
        runCurrent()
        assertEquals(1, api.swapsCalls)

        swaps.invite(" hen ", 500 * GB)
        runCurrent()
        val invite = assertNotNull(swaps.currentState.invite)
        assertEquals("hen", invite.name)
        assertEquals(600L, invite.secondsLeft(now))
        assertEquals(2, api.swapsCalls, "asked again at once")

        now = 3_000; advanceTimeBy(3_001)
        assertEquals(3, api.swapsCalls, "every 3 s while the invite shows")
        assertFalse(swaps.currentState.invite!!.joined)

        // Hen's server joins and offers a copy back.
        val offer = SwapOffer("o1", "hen", "hen", 1_000 * GB, 600_000)
        api.answer = SwapsAnswer(emptyList(), listOf(KeptSwap("hen", 500 * GB, 0, 2, null)), listOf(offer))
        now = 6_000; advanceTimeBy(3_000)
        assertTrue(swaps.currentState.invite!!.joined)
        assertEquals(offer, swaps.currentState.inviteOffer)

        // Joined with its offer seen: back to the slow pace.
        val calls = api.swapsCalls
        advanceTimeBy(29_000)
        assertEquals(calls, api.swapsCalls)
        advanceTimeBy(1_001)
        assertEquals(calls + 1, api.swapsCalls)

        swaps.accept(offer, "correct horse battery staple ink pen", plain = false)
        runCurrent()
        assertEquals(Triple("o1", "correct horse battery staple ink pen", false), api.accepted.single())
        assertIs<SwapOutcome.Joined>(swaps.currentState.outcome)
        swaps.clearOutcome()
        assertNull(swaps.currentState.outcome)

        swaps.closeInvite()
        assertNull(swaps.currentState.invite)
        watch.cancel()
    }

    @Test
    fun anExpiredInviteStopsTheQuickPace() = runTest {
        var now = 0L
        val api = FakeSwapApi()
        val swaps = SwapController(api, StandardTestDispatcher(testScheduler), now = { now })
        val watch = swaps.watch()
        swaps.invite("hen", GB)
        runCurrent()
        now = 600_000
        assertTrue(swaps.currentState.invite!!.expired(now))
        advanceTimeBy(3_001)
        val calls = api.swapsCalls
        advanceTimeBy(10_000)
        assertEquals(calls, api.swapsCalls)
        watch.cancel()
    }

    @Test
    fun joinSendsThePassphraseOnlyForAnEncryptedCopy() = runTest {
        val api = FakeSwapApi()
        val swaps = SwapController(api, StandardTestDispatcher(testScheduler))
        swaps.join(" journal://swap?x ", " dana ", "six words", plain = false, offerQuota = 100 * GB)
        runCurrent()
        assertEquals(listOf("journal://swap?x", "dana", "six words", false, 100 * GB), api.joins.last())
        assertIs<SwapOutcome.Joined>(swaps.currentState.outcome)
        assertTrue(api.infoCalls >= 1, "the copy key may have changed: asked again")

        swaps.joinWithoutOffer("journal://swap?y", "noa", "ignored", plain = true)
        runCurrent()
        assertEquals(listOf("journal://swap?y", "noa", null, true, null), api.joins.last())
    }

    @Test
    fun aRefusalIsTheOutcome() = runTest {
        val api = FakeSwapApi().apply { joinOutcome = SwapOutcome.Refused(SwapRefusal.WRONG_PASSPHRASE, "not it") }
        val swaps = SwapController(api, StandardTestDispatcher(testScheduler))
        swaps.join("journal://swap?x", "dana", "wrong", false, null)
        runCurrent()
        assertEquals(SwapOutcome.Refused(SwapRefusal.WRONG_PASSPHRASE, "not it"), swaps.currentState.outcome)
        assertFalse(swaps.currentState.acting)
    }

    @Test
    fun declineRemoveAndRevokeReachTheServer() = runTest {
        val api = FakeSwapApi()
        val offer = SwapOffer("o2", "noa", "noa", GB, 1)
        api.answer = SwapsAnswer(emptyList(), emptyList(), listOf(offer))
        val swaps = SwapController(api, StandardTestDispatcher(testScheduler))
        swaps.refresh(); runCurrent()
        api.answer = SwapsAnswer(emptyList(), emptyList(), emptyList())
        swaps.decline(offer); runCurrent()
        assertEquals(listOf("o2"), api.declined)
        assertTrue(swaps.currentState.offers.isEmpty())
        swaps.remove("dana"); runCurrent()
        swaps.revoke("noa", deleteFiles = true); runCurrent()
        assertEquals(listOf("dana"), api.removed)
        assertEquals(listOf("noa" to true), api.revoked)
    }

    @Test
    fun aFailedCallKeepsWhatWasShownAndSaysWhy() = runTest {
        val api = FakeSwapApi()
        val swaps = SwapController(api, StandardTestDispatcher(testScheduler))
        swaps.refresh(); runCurrent()
        api.fail = true
        swaps.refresh(); runCurrent()
        assertEquals("can't reach the server", swaps.currentState.error)
        assertTrue(swaps.currentState.canKeepFriends)
    }
}
