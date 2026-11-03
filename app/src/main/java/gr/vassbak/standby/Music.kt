package gr.vassbak.standby

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock

/**
 * Έλεγχος του player που παίζει (Spotify, Apple Music, YouTube Music κ.λπ.).
 * Χρειάζεται την πρόσβαση στις ειδοποιήσεις που ήδη έχει η εφαρμογή.
 */
object Music {

    data class Track(
        val app: String, val title: String, val artist: String, val art: Bitmap?,
        val playing: Boolean, val position: Long, val duration: Long
    )

    fun manager(ctx: Context) = ctx.getSystemService(MediaSessionManager::class.java)
    fun listenerComponent(ctx: Context) = ComponentName(ctx, NotifListener::class.java)

    /** Ο player που παίζει τώρα, αλλιώς ο πιο πρόσφατος. */
    fun controller(ctx: Context): MediaController? = try {
        val list = manager(ctx).getActiveSessions(listenerComponent(ctx))
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.metadata != null }
    } catch (_: SecurityException) { null } catch (_: Exception) { null }

    fun track(ctx: Context, c: MediaController): Track? {
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val st = c.playbackState
        val playing = st?.state == PlaybackState.STATE_PLAYING
        var pos = st?.position ?: 0L
        if (playing && st != null && st.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val app = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        } catch (_: Exception) { c.packageName }
        return Track(app, title, artist, art, playing, pos.coerceAtLeast(0), dur)
    }

    fun toggle(c: MediaController?) {
        val t = c?.transportControls ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
    }

    fun fmt(ms: Long): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
