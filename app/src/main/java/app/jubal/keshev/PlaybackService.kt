package app.jubal.keshev

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
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

/**
 * Plays audio in the background. The player is built with an audio renderer only,
 * so it physically cannot show video, and every picture is stripped before it can
 * reach the notification or the lock screen.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val saver = object : Runnable {
        override fun run() {
            savePosition()
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
        val dataSources = DefaultDataSource.Factory(this, RoutingHttpDataSource.Factory())

        val player = ExoPlayer.Builder(this, audioOnly)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_IMAGE, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                savePosition()
                handler.removeCallbacks(saver)
                if (isPlaying) handler.postDelayed(saver, 5_000)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() }?.let { Store.markDone(it) }
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
        val player = session?.player ?: return
        val id = player.currentMediaItem?.mediaId ?: return
        if (id.isEmpty()) return
        val position = player.currentPosition
        val duration = player.duration
        if (duration > 0 && position > duration - 20_000) {
            Store.markDone(id)
        } else if (position > 5_000) {
            Store.savePosition(id, position)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(saver)
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
