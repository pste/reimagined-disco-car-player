package dev.steo.autoproxy

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Player Media3 "finto": non produce audio, fa da specchio dello stato del
 * player web (via WebEngine) e gli inoltra i comandi che arrivano da
 * Android Auto / notifica media.
 *
 * Lo stato arriva dagli eventi mediaSession intercettati nella pagina;
 * i comandi escono come invocazioni degli action handler della pagina.
 */
class WebPlayer(
    looper: Looper,
    private val engine: WebEngine,
) : SimpleBasePlayer(looper), WebEngine.Listener {

    /** Fotografia dello stato del player web, aggiornata dagli eventi JS. */
    private data class WebState(
        val ready: Boolean = false,
        val playing: Boolean = false,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long = C.TIME_UNSET,
        val positionMs: Long = 0L,
        val positionSampledAt: Long = SystemClock.elapsedRealtime(),
        val rate: Float = 1f,
        val artwork: ByteArray? = null,
        val actions: Set<String> = emptySet(),
    )

    private var web = WebState()

    init {
        engine.listener = this
    }

    /* ------------------------------------------------------------------ *
     *  Stato verso Media3 (notifica, Android Auto)                        *
     * ------------------------------------------------------------------ */

    override fun getState(): State {
        val s = web

        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_RELEASE,
        )
        if ("nexttrack" in s.actions) {
            commands.add(Player.COMMAND_SEEK_TO_NEXT)
        }
        if ("previoustrack" in s.actions) {
            commands.add(Player.COMMAND_SEEK_TO_PREVIOUS)
        }
        if ("seekto" in s.actions) {
            commands.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(s.title)
            .setArtist(s.artist)
            .setAlbumTitle(s.album)
            .apply {
                if (s.artwork != null) {
                    setArtworkData(s.artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
            }
            .build()

        val item = MediaItemData.Builder("web-current")
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId("web-current")
                    .setMediaMetadata(metadata)
                    .build()
            )
            .setMediaMetadata(metadata)
            .setDurationUs(if (s.durationMs == C.TIME_UNSET) C.TIME_UNSET else s.durationMs * 1000)
            .setIsSeekable("seekto" in s.actions)
            .build()

        return State.Builder()
            .setAvailableCommands(commands.build())
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(if (s.ready) Player.STATE_READY else Player.STATE_IDLE)
            .setPlayWhenReady(s.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs { extrapolatedPositionMs() }
            .build()
    }

    private fun extrapolatedPositionMs(): Long {
        val s = web
        if (!s.playing) {
            return s.positionMs
        }
        val elapsed = SystemClock.elapsedRealtime() - s.positionSampledAt
        val pos = s.positionMs + (elapsed * s.rate).toLong()
        return if (s.durationMs != C.TIME_UNSET) pos.coerceAtMost(s.durationMs) else pos
    }

    /* ------------------------------------------------------------------ *
     *  Comandi da Media3 -> player web                                    *
     * ------------------------------------------------------------------ */

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        engine.sendAction(if (playWhenReady) "play" else "pause")
        // update ottimistico: l'evento playbackState dalla pagina confermerà
        web = web.copy(
            playing = playWhenReady,
            positionMs = extrapolatedPositionMs(),
            positionSampledAt = SystemClock.elapsedRealtime(),
        )
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: @Player.Command Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                engine.sendAction("nexttrack")
            }
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                engine.sendAction("previoustrack")
            }
            else -> {
                engine.sendAction("seekto", positionMs / 1000.0)
                web = web.copy(
                    positionMs = positionMs,
                    positionSampledAt = SystemClock.elapsedRealtime(),
                )
                invalidateState()
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        web = web.copy(ready = true)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.sendAction("pause")
        web = web.copy(playing = false, positionMs = extrapolatedPositionMs())
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        // la WebView (WebEngine) sopravvive al player: niente da rilasciare qui
        if (engine.listener === this) {
            engine.listener = null
        }
        return Futures.immediateVoidFuture()
    }

    /* ------------------------------------------------------------------ *
     *  Eventi dal player web (già sul main looper)                        *
     * ------------------------------------------------------------------ */

    override fun onActions(actions: Set<String>) {
        web = web.copy(ready = true, actions = actions)
        invalidateState()
    }

    override fun onMetadata(title: String?, artist: String?, album: String?) {
        // brano nuovo: azzera la posizione in attesa del prossimo setPositionState
        web = web.copy(
            ready = true,
            title = title,
            artist = artist,
            album = album,
            positionMs = 0L,
            positionSampledAt = SystemClock.elapsedRealtime(),
        )
        invalidateState()
    }

    override fun onArtwork(bytes: ByteArray?) {
        web = web.copy(artwork = bytes)
        invalidateState()
    }

    override fun onPlaying(playing: Boolean) {
        web = web.copy(
            ready = true,
            playing = playing,
            positionMs = extrapolatedPositionMs(),
            positionSampledAt = SystemClock.elapsedRealtime(),
        )
        invalidateState()
    }

    override fun onPosition(durationMs: Long, positionMs: Long, rate: Float) {
        web = web.copy(
            ready = true,
            durationMs = if (durationMs < 0) C.TIME_UNSET else durationMs,
            positionMs = positionMs,
            positionSampledAt = SystemClock.elapsedRealtime(),
            rate = rate,
        )
        invalidateState()
    }
}
