package com.chencohensolution.journal.enrollment

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Operation
import com.chencohensolution.journal.graphql.EnrollDeviceMutation
import com.chencohensolution.journal.graphql.EnrollmentPreviewQuery
import com.chencohensolution.journal.graphql.type.EnrollDeviceInput
import com.chencohensolution.journal.graphql.type.EnrollmentRejectedReason
import com.chencohensolution.journal.network.describe
import kotlin.io.encoding.Base64
import com.chencohensolution.journal.graphql.type.KeyProtection as GraphQlKeyProtection

/** The server turned the request down; see `EnrollmentRejectedReason` in the server schema. */
internal class EnrollmentRejectedException(val reason: EnrollmentRejectedReason) : Exception("Enrollment rejected: $reason")

/** Anything other than an answer from the server: TLS, network, HTTP or GraphQL errors. */
internal class EnrollmentTransportException(message: String) : Exception(message)

internal data class EnrollmentPreview(val user: String, val expiresAt: String)

internal data class IssuedCertificate(val user: String, val deviceName: String, val certificateDer: ByteArray, val expiresAt: String)

internal suspend fun previewEnrollment(apollo: ApolloClient, code: String): EnrollmentPreview {
    val result = apollo.query(EnrollmentPreviewQuery(code)).execute().dataOrThrow().enrollmentPreview
    result.onEnrollmentRejected?.let { throw EnrollmentRejectedException(it.reason) }
    val preview = result.onEnrollmentPreview ?: throw EnrollmentTransportException("Unexpected answer ${result.__typename}")
    return EnrollmentPreview(preview.user, preview.expiresAt)
}

internal suspend fun enrollDevice(
    apollo: ApolloClient,
    code: String,
    deviceName: String,
    csrDer: ByteArray,
    protection: KeyProtection,
): IssuedCertificate {
    val input = EnrollDeviceInput(
        code = code,
        deviceName = deviceName,
        csr = Base64.encode(csrDer),
        keyProtection = GraphQlKeyProtection.safeValueOf(protection.name),
    )
    val result = apollo.mutation(EnrollDeviceMutation(input)).execute().dataOrThrow().enrollDevice
    result.onEnrollmentRejected?.let { throw EnrollmentRejectedException(it.reason) }
    val enrolled = result.onEnrolledDevice ?: throw EnrollmentTransportException("Unexpected answer ${result.__typename}")
    return IssuedCertificate(enrolled.user, enrolled.deviceName, Base64.decode(enrolled.certificate), enrolled.expiresAt)
}

private fun <D : Operation.Data> ApolloResponse<D>.dataOrThrow(): D {
    exception?.let { throw EnrollmentTransportException(it.describe()) }
    errors?.firstOrNull()?.let { throw EnrollmentTransportException(it.message) }
    return data ?: throw EnrollmentTransportException("Empty response")
}
