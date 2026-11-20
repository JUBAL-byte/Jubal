package app.jubal.qeshev

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

/**
 * Plays audio in the background. The player only has an audio renderer, so it cannot
 * show video at all, and every picture is stripped before it can reach the
 * notification or the lock screen.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private var retriedId: String? = null

    // What is playing now, and how long it has really been heard (for recommendations)
    private var current: Track? = null
    private var listenedMs = 0L
    private var playingSince = 0L
    private var ended = false

    // Looks up the next YouTube item's audio link while the current one plays,
    // so moving on (by itself or with "next") starts without the lookup wait.
    private val prefetcher = Executors.newSingleThreadExecutor()
    private var prefetchedId: String? = null
    private val prefetch = Runnable { prefetchNext() }

    private val ticker = object : Runnable {
        override fun run() {
            savePosition()
            maybeQueueRecommendation()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val audioOnly = RenderersFactory { eventHandler, _, audioListener, _, _ ->
            arrayOf<Renderer>(
                MediaCodecAudioRenderer(this, MediaCodecSelector.DEFAULT, eventHandler, audioListener)
            )
        }
        val upstream = DefaultDataSource.Factory(this, RoutingHttpDataSource.Factory())
        val dataSources = ResolvingDataSource.Factory(upstream, StreamResolver(applicationContext))

        // Start after one second of audio instead of the default two and a half
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 1_000, 2_500)
            .build()

        player = ExoPlayer.Builder(this, audioOnly)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_IMAGE, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        player.setPlaybackSpeed(Store.speed())

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val now = SystemClock.elapsedRealtime()
                if (isPlaying) {
                    playingSince = now
                } else if (playingSince > 0) {
                    listenedMs += now - playingSince
                    playingSince = 0
                }
                savePosition()
                handler.removeCallbacks(ticker)
                if (isPlaying) handler.postDelayed(ticker, 3_000)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // The previous item finished by itself (AUTO) or the listener moved on (SEEK).
                finishCurrent(
                    completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                    skipped = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
                )
                val track = Track.of(mediaItem)
                current = track
                ended = false
                listenedMs = 0
                playingSince = if (player.isPlaying) SystemClock.elapsedRealtime() else 0
                if (track != null) Store.recordStart(track, Track.isActive(mediaItem))
                retriedId = null
                handler.postDelayed({ maybeQueueRecommendation() }, 4_000)
                handler.removeCallbacks(prefetch)
                handler.postDelayed(prefetch, 1_500)
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                // Something was added to or removed from the queue
                handler.removeCallbacks(prefetch)
                handler.postDelayed(prefetch, 1_000)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() }?.let { Store.markDone(it) }
                    if (!ended) {
                        finishCurrent(completed = true, skipped = false)
                        ended = true
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // A YouTube link can expire mid-way. Try once more with a fresh link.
                val track = Track.of(player.currentMediaItem) ?: return
                if (track.isYouTube && retriedId != track.id) {
                    retriedId = track.id
                    YouTube.invalidate(track.videoId)
                    val position = player.currentPosition
                    player.prepare()
                    player.seekTo(position)
                    player.play()
                }
            }
        })

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val built = MediaSession.Builder(this, NoArtworkPlayer(player))
            .setSessionActivity(openApp)
            .setBitmapLoader(NoBitmapLoader)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>,
                ): ListenableFuture<List<MediaItem>> {
                    val resolved = mediaItems.map { item ->
                        val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                        if (uri == null) item else item.buildUpon().setUri(uri).build()
                    }
                    return Futures.immediateFuture(resolved)
                }
            })
            .build()
        session = built
        addSession(built)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private fun savePosition() {
        val track = Track.of(player.currentMediaItem) ?: return
        if (track.kind == Kind.MUSIC) return
        val position = player.currentPosition
        val duration = player.duration
        if (duration > 0 && position > duration - 20_000) {
            Store.markDone(track.id)
        } else if (position > 5_000) {
            Store.savePosition(track.id, position, if (duration > 0) duration else 0)
        }
    }

    /** Records how the item that just stopped was heard: finished, or skipped within 30 seconds. */
    private fun finishCurrent(completed: Boolean, skipped: Boolean) {
        val track = current ?: return
        if (ended) return
        val now = SystemClock.elapsedRealtime()
        if (playingSince > 0) {
            listenedMs += now - playingSince
            playingSince = if (player.isPlaying) now else 0
        }
        val early = skipped && listenedMs < 30_000 && (track.durationSec == 0L || track.durationSec > 45)
        Store.recordEnd(track, listenedMs, completed, early)
        current = null
    }

    /** Keeps music and videos going: when the queue is about to run out, adds what YouTube suggests next. */
    private fun maybeQueueRecommendation() {
        if (!Store.autoplay()) return
        val track = Track.of(player.currentMediaItem) ?: return
        if (track.kind == Kind.PODCAST || player.hasNextMediaItem()) return
        val queued = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
        val recent = Store.recentIds(60)
        val next = Store.related(track.id)?.items?.firstOrNull {
            it.id !in queued && it.id !in recent && (track.kind != Kind.MUSIC || Recs.looksLikeMusic(it))
        } ?: return
        // Added by the app, not chosen by the listener, so it won't count as a preference.
        player.addMediaItem(next.toMediaItem(active = false))
    }

    private fun prefetchNext() {
        val index = player.nextMediaItemIndex
        if (index == C.INDEX_UNSET) return
        val next = Track.of(player.getMediaItemAt(index)) ?: return
        if (!next.isYouTube || next.id == prefetchedId || Store.downloadFor(next.id) != null) return
        prefetchedId = next.id
        prefetcher.execute { runCatching { YouTube.audioFor(next.videoId, next.kind) } }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        prefetcher.shutdownNow()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/** Hides any cover picture that is embedded inside an audio file. */
@OptIn(UnstableApi::class)
class NoArtworkPlayer(player: Player) : ForwardingPlayer(player) {
    override fun getMediaMetadata(): MediaMetadata = strip(super.getMediaMetadata())
    override fun getPlaylistMetadata(): MediaMetadata = strip(super.getPlaylistMetadata())

    private fun strip(m: MediaMetadata): MediaMetadata =
        m.buildUpon().setArtworkData(null, null).setArtworkUri(null).build()
}

/** Never loads a picture, so notifications and the lock screen stay text-only. */
@OptIn(UnstableApi::class)
object NoBitmapLoader : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = false
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        Futures.immediateFailedFuture(IllegalStateException("Pictures are disabled"))
    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        Futures.immediateFailedFuture(IllegalStateException("Pictures are disabled"))
    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? = null
}
