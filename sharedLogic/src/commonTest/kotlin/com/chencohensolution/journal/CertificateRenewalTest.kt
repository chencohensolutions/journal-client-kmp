package com.chencohensolution.journal

import com.chencohensolution.journal.enrollment.CertificateRenewal
import com.chencohensolution.journal.enrollment.DeviceKey
import com.chencohensolution.journal.enrollment.DeviceKeyStore
import com.chencohensolution.journal.enrollment.EnrollmentController
import com.chencohensolution.journal.enrollment.IssuedCertificate
import com.chencohensolution.journal.enrollment.KeyProtection
import com.chencohensolution.journal.enrollment.RenewApi
import com.chencohensolution.journal.enrollment.RenewalResult
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private class Keys : DeviceKeyStore {
    val keys = mutableMapOf<String, ByteArray?>() // alias → certificate
    var notAfter: Long? = null

    override fun generate(alias: String): DeviceKey {
        keys[alias] = null
        return object : DeviceKey {
            override val protection = KeyProtection.TEE
            override val publicKeyInfo = ByteArray(91)
            override fun sign(data: ByteArray) = byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02)
        }
    }

    override fun saveCertificate(alias: String, certificateDer: ByteArray) {
        keys[alias] = certificateDer
    }

    override fun delete(alias: String) {
        keys.remove(alias)
    }

    override fun certificateNotAfter(alias: String) = notAfter
}

private class Memory : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

@OptIn(ExperimentalTime::class)
class CertificateRenewalTest {
    private val now = Instant.parse("2027-10-01T12:00:00Z")
    private val day = 24 * 60 * 60 * 1000L
    private val old = EnrollmentController.IDENTITY_ALIAS_PREFIX + "1"

    private class Setup(val settings: JournalSettings, val keys: Keys, val renewal: CertificateRenewal, val switched: MutableList<String>)

    private fun TestScope.setup(expiresIn: Long?, api: RenewApi, connects: Boolean = true): Setup {
        val settings = JournalSettings(Memory())
        settings.identityAlias = old
        settings.certificateExpiresAt = expiresIn?.let { now.toEpochMilliseconds() + it }
        val keys = Keys().apply { keys[old] = byteArrayOf(1) }
        val switched = mutableListOf<String>()
        val renewal = CertificateRenewal(
            settings = settings, keyStore = keys, api = api, test = { connects },
            onRenewed = { switched += it; settings.identityAlias = it },
            dispatcher = StandardTestDispatcher(testScheduler), workDispatcher = StandardTestDispatcher(testScheduler),
            clock = object : Clock { override fun now() = now },
        )
        return Setup(settings, keys, renewal, switched)
    }

    private val renewed = RenewApi { _, _ -> IssuedCertificate("hen", "Pixel", byteArrayOf(9), "2028-10-01T12:00:00Z") }

    @Test
    fun aCertificateFarFromItsEndIsLeftAlone() = runTest {
        val s = setup(expiresIn = 60 * day, api = { _, _ -> error("asked") })
        assertEquals(RenewalResult.NotDue, s.renewal.renewNow())
        assertEquals(setOf(old), s.keys.keys.keys)
    }

    @Test
    fun inItsLast30DaysItIsRenewedAndTheOldKeyGoes() = runTest {
        val s = setup(expiresIn = 20 * day, api = renewed)
        val result = s.renewal.renewNow()
        assertIs<RenewalResult.Renewed>(result)
        assertEquals(Instant.parse("2028-10-01T12:00:00Z").toEpochMilliseconds(), result.validUntilMillis)
        val alias = s.switched.single()
        assertEquals(alias, s.settings.identityAlias)
        assertEquals(setOf(alias), s.keys.keys.keys, "the old key is deleted, the new one kept")
        assertTrue(s.keys.keys[alias]!!.contentEquals(byteArrayOf(9)))
        assertEquals(result.validUntilMillis, s.settings.certificateExpiresAt)
        assertEquals(RenewalResult.NotDue, s.renewal.renewNow(), "a year left now")
    }

    @Test
    fun aRenewalThatFailsLeavesTheDeviceAsItWas() = runTest {
        for (s in listOf(
            setup(expiresIn = 5 * day, api = { _, _ -> error("offline") }),
            setup(expiresIn = 5 * day, api = renewed, connects = false),
        )) {
            assertIs<RenewalResult.Failed>(s.renewal.renewNow())
            assertEquals(old, s.settings.identityAlias)
            assertEquals(setOf(old), s.keys.keys.keys, "the new key is deleted")
            assertTrue(s.switched.isEmpty())
        }
    }

    @Test
    fun theEndIsReadFromTheKeyStoreWhenItWasntKept() = runTest {
        val s = setup(expiresIn = null, api = renewed)
        assertEquals(RenewalResult.NotDue, s.renewal.renewNow(), "not known at all")
        s.keys.notAfter = now.toEpochMilliseconds() + 10 * day
        assertIs<RenewalResult.Renewed>(s.renewal.renewNow())
        assertNull(s.keys.keys[old])
    }
}
