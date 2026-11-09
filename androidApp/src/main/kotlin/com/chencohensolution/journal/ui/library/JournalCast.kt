package com.chencohensolution.journal.ui.library

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.chencohensolution.journal.cast.CAST_NAMESPACE
import com.chencohensolution.journal.cast.CastChannel
import com.chencohensolution.journal.cast.CastController
import com.chencohensolution.journal.R
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

// Casting to a TV with Google Cast (docs/cast.md): the Journal's own receiver page on the TV, registered in Google's
// Cast console under [JournalCast.APP_ID]. This finds the TVs, opens and closes the session, and hands its messages to
// the shared CastController; CastServer serves the originals.

/** Google Cast's options for the app: our receiver. Named in the manifest; Cast reads it on first use. */
class JournalCastOptions : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(JournalCast.APP_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** A TV Cast found on the Wi-Fi, for the picker. */
internal data class CastTv(val id: String, val name: String, val description: String?, val connecting: Boolean)

/** The app's one link to Google Cast: the TVs found, the session, and what is said over it. */
internal object JournalCast {
    /**
     * The receiver's app id from Google's Cast console (cast.google.com/publish): it points at
     * https://chencohensolutions.github.io/journal-client-kmp/receiver/ (registered Nov 9, 2026). Empty: the Cast button
     * doesn't show.
     */
    const val APP_ID = "95B1757E"

    private const val TAG = "JournalCast"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private lateinit var app: Context
    private var castContext: CastContext? = null
    private var server: CastServer? = null
    private var session: CastSession? = null
    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()

    /** What is said to the TV, and what it said back. */
    val controller = CastController(scope) { id -> withContext(Dispatchers.IO) { server?.bytesOf(id) } }

    private val _tvs = MutableStateFlow<List<CastTv>>(emptyList())

    /** The TVs on the Wi-Fi, while the picker looks for them ([lookForTvs]). */
    val tvs: StateFlow<List<CastTv>> = _tvs.asStateFlow()

    /** Whether casting can be offered: the receiver is registered and Google Play services has Cast. */
    fun available(context: Context): Boolean = start(context)

    /** The phone's web server for the originals, while casting. */
    fun server(): CastServer? = server?.takeIf { session != null }

    /** Starts Cast on first use; false when it can't run here. */
    private fun start(context: Context): Boolean {
        if (APP_ID.isBlank()) return false
        if (started) return castContext != null
        started = true
        app = context.applicationContext
        castContext = try {
            @Suppress("DEPRECATION")
            CastContext.getSharedInstance(app)
        } catch (e: Exception) {
            Log.w(TAG, "Cast isn't available", e)
            null
        }
        val cast = castContext ?: return false
        cast.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
        cast.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let(::opened)
        return true
    }

    private val selector by lazy {
        MediaRouteSelector.Builder().addControlCategory(CastMediaControlIntent.categoryForCast(APP_ID)).build()
    }

    private val routes = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = listTvs(router)
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = listTvs(router)
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = listTvs(router)
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = listTvs(router)
    }

    private fun listTvs(router: MediaRouter) {
        _tvs.value = router.routes
            .filter { !it.isDefaultOrBluetooth && it.isEnabled && it.matchesSelector(selector) }
            .map { CastTv(it.id, it.name, it.description, it.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING) }
    }

    /** Looks for TVs on the Wi-Fi while the picker is open: call [stopLooking] when it closes. */
    fun lookForTvs(context: Context) {
        if (!start(context)) return
        val router = MediaRouter.getInstance(app)
        router.addCallback(selector, routes, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        listTvs(router)
    }

    fun stopLooking() {
        if (castContext == null) return
        MediaRouter.getInstance(app).removeCallback(routes)
    }

    /** Casts to [tv]: Cast opens the session, and the receiver page starts on the TV. */
    fun castTo(tv: CastTv) {
        val router = MediaRouter.getInstance(app)
        router.routes.firstOrNull { it.id == tv.id }?.let(router::selectRoute)
    }

    /** Stops casting: the TV goes back to what it showed before. */
    fun stopCasting() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    private fun opened(cast: CastSession) {
        if (session === cast) return
        session = cast
        val server = server ?: CastServer(app).also { server = it }
        server.start()
        keepWifiAwake(true)
        CastService.start(app, cast.castDevice?.friendlyName)
        runCatching {
            cast.setMessageReceivedCallbacks(CAST_NAMESPACE) { _, namespace, message ->
                if (namespace == CAST_NAMESPACE) controller.received(message)
            }
        }.onFailure { Log.w(TAG, "can't listen to the receiver", it) }
        controller.connected(CastChannel { message -> send(cast, message) }, cast.castDevice?.friendlyName)
    }

    private fun closed() {
        session?.let { cast -> runCatching { cast.removeMessageReceivedCallbacks(CAST_NAMESPACE) } }
        session = null
        server?.stop()
        keepWifiAwake(false)
        if (::app.isInitialized) CastService.stop(app)
        controller.disconnected()
    }

    /**
     * Holds the Wi-Fi awake while casting. Without it the phone's Wi-Fi dozes between packets and drops the TV's
     * requests to [CastServer] for seconds at a time (seen Nov 9, 2026): photos then fail and videos stall.
     */
    @Suppress("DEPRECATION")
    private fun keepWifiAwake(awake: Boolean) {
        if (!awake) {
            wifiLocks.forEach { if (it.isHeld) it.release() }
            wifiLocks.clear()
            return
        }
        if (wifiLocks.isNotEmpty()) return
        val wifi = app.getSystemService(WifiManager::class.java) ?: return
        // Low latency works only while the app counts as in front: CastService keeps it so while casting. High
        // performance covers older Android, where the system still honours it.
        val modes = listOfNotNull(
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY.takeIf { Build.VERSION.SDK_INT >= 29 },
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
        )
        for (mode in modes) {
            runCatching { wifi.createWifiLock(mode, "$TAG:$mode").apply { setReferenceCounted(false); acquire() } }
                .onSuccess(wifiLocks::add)
                .onFailure { Log.w(TAG, "can't keep the Wi-Fi awake", it) }
        }
    }

    private suspend fun send(cast: CastSession, message: String): Boolean = suspendCancellableCoroutine { done ->
        try {
            cast.sendMessage(CAST_NAMESPACE, message).setResultCallback { status ->
                if (!status.isSuccess) Log.w(TAG, "message not sent: ${status.statusCode}")
                if (done.isActive) done.resume(status.isSuccess)
            }
        } catch (e: Exception) {
            Log.w(TAG, "message not sent", e)
            if (done.isActive) done.resume(false)
        }
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = opened(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = opened(session)
        override fun onSessionEnded(session: CastSession, error: Int) = closed()
        override fun onSessionSuspended(session: CastSession, reason: Int) = closed()
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.w(TAG, "couldn't cast: $error")
            closed()
            // Said, not left silent: 2004 is a TV that doesn't know our receiver (not published, or not a test device).
            val text = if (error == CastStatusCodes.APPLICATION_NOT_FOUND) R.string.cast_not_on_this_tv else R.string.cast_start_failed
            android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show()
        }
        override fun onSessionResumeFailed(session: CastSession, error: Int) = closed()
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
    }
}
