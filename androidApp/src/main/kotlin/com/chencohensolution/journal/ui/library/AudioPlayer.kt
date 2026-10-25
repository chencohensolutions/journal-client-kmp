package com.chencohensolution.journal.ui.library

import android.content.Context
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as PlayerItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.SoundRecording
import com.chencohensolution.journal.serverStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What plays: one sound recording at a time, at [positionMillis], and whether it is playing or paused there. */
internal data class AudioPlayback(val sha256: String, val positionMillis: Long, val playing: Boolean, val speed: Float = 1f)

/** The app's [AudioPlayer]; null in previews and the UI lab, where nothing plays. */
internal val LocalAudioPlayer = staticCompositionLocalOf<AudioPlayer?> { null }

/**
 * Plays the server's sound recordings (Moments and a recording's page): one at a time, never by itself, only while the
 * app is in front (the app pauses it when it leaves the screen: no background service, no lock-screen controls). The
 * original streams from `/media/{sha256}` with Range, so a 3-hour recording starts at once and seeks anywhere. Short
 * ones (calls, voice messages) go through the video cache; long ones aren't written there, as one could push out every
 * kept preview. It takes audio focus, so a feed video playing aloud falls silent (FeedVideo gives way). Positions are
 * remembered while the app runs.
 */
@OptIn(UnstableApi::class)
internal class AudioPlayer(private val context: Context, private val scope: CoroutineScope) {
    private val _playback = MutableStateFlow<AudioPlayback?>(null)
    val playback: StateFlow<AudioPlayback?> = _playback.asStateFlow()

    private var player: ExoPlayer? = null
    private var current: SoundRecording? = null
    private var opening: Job? = null
    private var ticker: Job? = null
    private val positions = HashMap<String, Long>()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                current?.let { positions.remove(it.sha256) }
                player?.run {
                    pause()
                    seekTo(0)
                }
            }
            publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(context, R.string.audio_failed, Toast.LENGTH_SHORT).show()
            stop()
        }
    }

    /** Plays [recording], from [fromMillis] or where it was left; the one playing before stops. */
    fun play(recording: SoundRecording, fromMillis: Long? = null) {
        val playing = player
        if (playing != null && current?.sha256 == recording.sha256) {
            fromMillis?.let(playing::seekTo)
            playing.play()
            publish()
            return
        }
        stop()
        current = recording
        val start = fromMillis ?: positions[recording.sha256] ?: 0L
        _playback.value = AudioPlayback(recording.sha256, start, playing = true)
        opening = scope.launch {
            val source = withContext(Dispatchers.IO) { dataSource(recording) }
            if (source == null || current !== recording) {
                if (source == null) Toast.makeText(context, R.string.audio_failed, Toast.LENGTH_SHORT).show()
                if (current === recording) stop()
                return@launch
            }
            val exo = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(source.first, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)))
                .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build()
            exo.addListener(listener)
            exo.setMediaItem(source.second, start)
            exo.prepare()
            exo.playWhenReady = true
            player = exo
            ticker = scope.launch {
                while (isActive) {
                    publish()
                    delay(TICK_MILLIS)
                }
            }
        }
    }

    fun pause() {
        player?.pause()
        publish()
    }

    /** Goes to [millis] into [recording]: playing it from there if it isn't the one playing. */
    fun seek(recording: SoundRecording, millis: Long) {
        val at = millis.coerceIn(0, recording.durationMillis.takeIf { it > 0 } ?: Long.MAX_VALUE)
        if (current?.sha256 != recording.sha256 || player == null) return play(recording, at)
        player?.seekTo(at)
        publish()
    }

    fun setSpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed)
        publish()
    }

    /** Stops playing and lets go of the player; where it was is remembered. */
    fun stop() {
        opening?.cancel()
        ticker?.cancel()
        player?.let { exo ->
            current?.let { positions[it.sha256] = exo.currentPosition }
            exo.removeListener(listener)
            exo.release()
        }
        player = null
        current = null
        _playback.value = null
    }

    private fun publish() {
        val recording = current ?: return
        val exo = player ?: return
        _playback.value = AudioPlayback(recording.sha256, exo.currentPosition, exo.playWhenReady, exo.playbackParameters.speed)
    }

    /** Where [recording]'s bytes come from: the server, through the video cache when it is short. Off the main thread. */
    private fun dataSource(recording: SoundRecording): Pair<DataSource.Factory, PlayerItem>? {
        val stream = serverStream(context, recording.sha256) ?: return null
        val upstream = OkHttpDataSource.Factory(stream.client)
        val source: DataSource.Factory = if (recording.durationMillis in 1..CACHED_MILLIS) {
            CacheDataSource.Factory()
                .setCache(ServerVideos.cache(context))
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            upstream
        }
        return source to PlayerItem.Builder().setUri(stream.url).setCustomCacheKey(recording.sha256).build()
    }

    private companion object {
        const val TICK_MILLIS = 250L

        /** Recordings up to this long are kept in the video cache once played. */
        const val CACHED_MILLIS = 10 * 60_000L
    }
}
