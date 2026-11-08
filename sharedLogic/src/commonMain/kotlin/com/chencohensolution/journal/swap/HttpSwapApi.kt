package com.chencohensolution.journal.swap

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Optional
import com.chencohensolution.journal.graphql.AcceptSwapOfferMutation
import com.chencohensolution.journal.graphql.DeclineSwapOfferMutation
import com.chencohensolution.journal.graphql.JoinSwapMutation
import com.chencohensolution.journal.graphql.RemoveSwapMutation
import com.chencohensolution.journal.graphql.RevokeSwapMutation
import com.chencohensolution.journal.graphql.SwapInfoQuery
import com.chencohensolution.journal.graphql.SwapInviteMutation
import com.chencohensolution.journal.graphql.SwapsQuery
import com.chencohensolution.journal.graphql.fragment.JoinSwapResultFields
import com.chencohensolution.journal.graphql.fragment.MySwapFields
import com.chencohensolution.journal.graphql.fragment.SwapsFields
import com.chencohensolution.journal.server.ServerError
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.server.firstServerError
import com.chencohensolution.journal.server.toCopy
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import com.chencohensolution.journal.graphql.type.CopyKeyKind as ServerCopyKeyKind
import com.chencohensolution.journal.graphql.type.SwapRefusal as ServerSwapRefusal

internal class HttpSwapApi(private val http: ServerHttp) : SwapApi {
    override suspend fun info(): SwapInfo? = call {
        val response = it.query(SwapInfoQuery()).execute()
        response.exception?.let { e -> throw e }
        // A server older than 1.1.0 refuses the query: it doesn't know serverInfo.swap.
        if (response.errors?.any { e -> "swap" in e.message && ("Cannot query" in e.message || "unknown field" in e.message.lowercase()) } == true) {
            return@call null
        }
        firstServerError(response.errors)?.let { e -> throw e }
        val info = response.data?.serverInfo ?: throw ServerError("The server sent no answer", null)
        SwapInfo(
            available = info.swap.available,
            freeSpace = info.swap.freeSpace,
            copyKey = info.swap.copyKey.toKind(),
            serverId = info.discovery?.id,
        )
    }

    override suspend fun swaps() = call {
        val response = it.query(SwapsQuery()).execute()
        answer(response.exception, response.errors, response.data?.swaps?.swapsFields?.toAnswer())
    }

    override suspend fun invite(name: String, quota: Long) = call {
        val response = it.mutation(SwapInviteMutation(name, quota)).execute()
        val invite = answer(response.exception, response.errors, response.data?.swapInvite)
        ShownInvite(name, quota, invite.link, invite.code, millis(invite.expiresAt) ?: 0)
    }

    override suspend fun join(link: String, name: String, passphrase: String?, plain: Boolean, offerQuota: Long?) = call {
        val response = it.mutation(
            JoinSwapMutation(
                link = link,
                name = name,
                passphrase = Optional.presentIfNotNull(passphrase),
                plain = Optional.present(plain),
                offerQuota = Optional.presentIfNotNull(offerQuota),
            )
        ).execute()
        answer(response.exception, response.errors, response.data?.joinSwap?.joinSwapResultFields).toOutcome()
    }

    override suspend fun accept(id: String, passphrase: String?, plain: Boolean) = call {
        val response = it.mutation(
            AcceptSwapOfferMutation(id, Optional.presentIfNotNull(passphrase), Optional.present(plain))
        ).execute()
        answer(response.exception, response.errors, response.data?.acceptSwapOffer?.joinSwapResultFields).toOutcome()
    }

    override suspend fun decline(id: String) = call {
        val response = it.mutation(DeclineSwapOfferMutation(id)).execute()
        answer(response.exception, response.errors, response.data?.declineSwapOffer)
    }

    override suspend fun remove(name: String) = call {
        val response = it.mutation(RemoveSwapMutation(name)).execute()
        answer(response.exception, response.errors, response.data?.removeSwap)
    }

    override suspend fun revoke(name: String, deleteFiles: Boolean) = call {
        val response = it.mutation(RevokeSwapMutation(name, Optional.present(deleteFiles))).execute()
        answer(response.exception, response.errors, response.data?.revokeSwap)
    }

    private suspend fun <T> call(block: suspend (ApolloClient) -> T): T =
        http.graphql(block) ?: throw ServerError("This device isn't registered", null)

    private fun <T> answer(exception: Exception?, errors: List<Error>?, value: T?): T {
        exception?.let { throw it }
        firstServerError(errors)?.let { throw it }
        return value ?: throw ServerError("The server sent no answer", null)
    }
}

@OptIn(ExperimentalTime::class)
private fun millis(time: String?): Long? = time?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

private fun ServerCopyKeyKind.toKind() = when (this) {
    ServerCopyKeyKind.PASSPHRASE -> CopyKeyKind.PASSPHRASE
    ServerCopyKeyKind.KEY_FILE -> CopyKeyKind.KEY_FILE
    else -> CopyKeyKind.NONE
}

private fun MySwapFields.toSwap() = MySwap(name, quota, millis(addedAt) ?: 0, encrypted, dataCopy = copy?.dataCopyFields?.toCopy())

private fun SwapsFields.toAnswer() = SwapsAnswer(
    mine = mine.map { it.mySwapFields.toSwap() },
    kept = kept.map { KeptSwap(it.name, it.quota, it.used, millis(it.addedAt) ?: 0, millis(it.revokedAt)) },
    offers = offers.map { SwapOffer(it.id, it.from, it.name, it.quota, millis(it.expiresAt) ?: 0) },
)

private fun JoinSwapResultFields.toOutcome(): SwapOutcome {
    onSwapJoined?.let { return SwapOutcome.Joined(it.swap.mySwapFields.toSwap(), it.offered) }
    val refused = onSwapRefused ?: return SwapOutcome.Refused(SwapRefusal.OTHER, "The server sent an answer this app doesn't know")
    return SwapOutcome.Refused(refusal(refused.reason), refused.message)
}

internal fun refusal(reason: ServerSwapRefusal): SwapRefusal = when (reason) {
    ServerSwapRefusal.INVALID_LINK -> SwapRefusal.INVALID_LINK
    ServerSwapRefusal.CODE_REFUSED -> SwapRefusal.CODE_REFUSED
    ServerSwapRefusal.UNREACHABLE -> SwapRefusal.UNREACHABLE
    ServerSwapRefusal.OWN_SERVER -> SwapRefusal.OWN_SERVER
    ServerSwapRefusal.NAME_TAKEN -> SwapRefusal.NAME_TAKEN
    ServerSwapRefusal.NEEDS_PASSPHRASE -> SwapRefusal.NEEDS_PASSPHRASE
    ServerSwapRefusal.WEAK_PASSPHRASE -> SwapRefusal.WEAK_PASSPHRASE
    ServerSwapRefusal.WRONG_PASSPHRASE -> SwapRefusal.WRONG_PASSPHRASE
    ServerSwapRefusal.NO_SPACE -> SwapRefusal.NO_SPACE
    ServerSwapRefusal.OFFER_GONE -> SwapRefusal.OFFER_GONE
    ServerSwapRefusal.ALREADY_SWAPPED -> SwapRefusal.ALREADY_SWAPPED
    else -> SwapRefusal.OTHER
}
